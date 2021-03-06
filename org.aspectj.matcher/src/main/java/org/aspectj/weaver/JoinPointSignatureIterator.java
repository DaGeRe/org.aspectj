/* *******************************************************************
 * Copyright (c) 2005 Contributors.
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Eclipse Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://eclipse.org/legal/epl-v10.html 
 *  
 * Contributors: 
 *   Adrian Colyer			Initial implementation
 * ******************************************************************/
package org.aspectj.weaver;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Iterates over the signatures of a join point, calculating new signatures lazily to minimize processing and to avoid unneccessary
 * "can't find type" errors. Iterator can be cached and reused by calling the "reset" method between iterations.
 */
public class JoinPointSignatureIterator implements Iterator<JoinPointSignature> {

	ResolvedType firstDefiningType;

	private Member signaturesOfMember;
	private ResolvedMember firstDefiningMember;
	private World world;
	private List<JoinPointSignature> discoveredSignatures = new ArrayList<>();
	private List<JoinPointSignature> additionalSignatures = Collections.emptyList();
	private Iterator<JoinPointSignature> discoveredSignaturesIterator = null;
	private Iterator<ResolvedType> superTypeIterator = null;
	private boolean isProxy = false;
	private Set<ResolvedType> visitedSuperTypes = new HashSet<>();
	private List<SearchPair> yetToBeProcessedSuperMembers = null;

	private boolean iteratingOverDiscoveredSignatures = true;
	private boolean couldBeFurtherAsYetUndiscoveredSignatures = true;
	private final static UnresolvedType jlrProxy = UnresolvedType.forSignature("Ljava/lang/reflect/Proxy;");

	public JoinPointSignatureIterator(Member joinPointSignature, World world) {
		this.signaturesOfMember = joinPointSignature;
		this.world = world;
		addSignaturesUpToFirstDefiningMember();
		if (!shouldWalkUpHierarchy()) {
			couldBeFurtherAsYetUndiscoveredSignatures = false;
		}
	}

	public void reset() {
		discoveredSignaturesIterator = discoveredSignatures.iterator();
		additionalSignatures.clear();
		iteratingOverDiscoveredSignatures = true;
	}

	public boolean hasNext() {
		if (iteratingOverDiscoveredSignatures && discoveredSignaturesIterator.hasNext()) {
			return true;
		} else if (couldBeFurtherAsYetUndiscoveredSignatures) {
			if (additionalSignatures.size() > 0) {
				return true;
			} else {
				return findSignaturesFromSupertypes();
			}
		} else {
			return false;
		}
	}

	public JoinPointSignature next() {
		if (iteratingOverDiscoveredSignatures && discoveredSignaturesIterator.hasNext()) {
			return discoveredSignaturesIterator.next();
		} else {
			if (additionalSignatures.size() > 0) {
				return additionalSignatures.remove(0);
			}
		}
		throw new NoSuchElementException();
	}

	public void remove() {
		throw new UnsupportedOperationException("can't remove from JoinPointSignatureIterator");
	}

	/**
	 * Walk up the hierarchy creating one member for each type up to and including the first defining type.
	 */
	private void addSignaturesUpToFirstDefiningMember() {
		ResolvedType originalDeclaringType = signaturesOfMember.getDeclaringType().resolve(world);
		ResolvedType superType = originalDeclaringType.getSuperclass();
		if (superType != null && superType.equals(jlrProxy)) {
			// Proxy types are generated without any regard to generics (pr268419) and so the member walking
			// should also ignore them
			isProxy = true;
		}

		// is it the array constructor join point?
		if (world.isJoinpointArrayConstructionEnabled() && originalDeclaringType.isArray()) {
			Member m = signaturesOfMember;
			ResolvedMember rm = new ResolvedMemberImpl(m.getKind(), m.getDeclaringType(), m.getModifiers(), m.getReturnType(), m
					.getName(), m.getParameterTypes());
			discoveredSignatures.add(new JoinPointSignature(rm, originalDeclaringType));
			couldBeFurtherAsYetUndiscoveredSignatures = false;
			return;
		}

		firstDefiningMember = (signaturesOfMember instanceof ResolvedMember ? 
		    (ResolvedMember) signaturesOfMember: signaturesOfMember.resolve(world));

		if (firstDefiningMember == null) {
			couldBeFurtherAsYetUndiscoveredSignatures = false;
			return;
		}

		// declaringType can be unresolved if we matched a synthetic member generated by Aj...
		// should be fixed elsewhere but add this resolve call on the end for now so that we can
		// focus on one problem at a time...
		firstDefiningType = firstDefiningMember.getDeclaringType().resolve(world);
		if (firstDefiningType != originalDeclaringType) {
			if (signaturesOfMember.getKind() == Member.CONSTRUCTOR) {
				return;
			}
		}

		if (originalDeclaringType == firstDefiningType) {
			// a common case
			discoveredSignatures.add(new JoinPointSignature(firstDefiningMember, originalDeclaringType));
		} else {
			List<ResolvedType> declaringTypes = new ArrayList<>();
			accumulateTypesInBetween(originalDeclaringType, firstDefiningType, declaringTypes);
			for (ResolvedType declaringType : declaringTypes) {
				discoveredSignatures.add(new JoinPointSignature(firstDefiningMember, declaringType));
			}
		}
	}

	/**
	 * Build a list containing every type between subtype and supertype, inclusively.
	 */
	private void accumulateTypesInBetween(ResolvedType subType, ResolvedType superType, List<ResolvedType> types) {
		types.add(subType);
		if (subType == superType) {
			return;
		} else {
			for (Iterator<ResolvedType> iter = subType.getDirectSupertypes(); iter.hasNext();) {
				ResolvedType parent = iter.next();
				if (superType.isAssignableFrom(parent, true)) {
					accumulateTypesInBetween(parent, superType, types);
				}
			}
		}
	}

	private boolean shouldWalkUpHierarchy() {
		if (signaturesOfMember.getKind() == Member.CONSTRUCTOR) {
			return false;
		}
		if (signaturesOfMember.getKind() == Member.FIELD) {
			return false;
		}
		if (Modifier.isStatic(signaturesOfMember.getModifiers())) {
			return false;
		}
		return true;
	}

	private boolean findSignaturesFromSupertypes() {
		iteratingOverDiscoveredSignatures = false;
		if (superTypeIterator == null) {
			superTypeIterator = firstDefiningType.getDirectSupertypes();
		}
		if (superTypeIterator.hasNext()) {
			ResolvedType superType = superTypeIterator.next();
			if (isProxy && (superType.isGenericType() || superType.isParameterizedType())) {
				superType = superType.getRawType();
			}
			if (visitedSuperTypes.contains(superType)) {
				return findSignaturesFromSupertypes();
			} else {
				// we haven't looked in this type yet
				visitedSuperTypes.add(superType);
				if (superType.isMissing()) {
					// issue a warning, stop looking for join point signatures in this line
					warnOnMissingType(superType);
					return findSignaturesFromSupertypes();
				}
				ResolvedMemberImpl foundMember = (ResolvedMemberImpl) superType.lookupResolvedMember(firstDefiningMember, true,
						isProxy);
				if (foundMember != null && isVisibleTo(firstDefiningMember, foundMember)) {
					List<ResolvedType> declaringTypes = new ArrayList<>();
					// declaring type can be unresolved if the member can from an ITD...
					ResolvedType resolvedDeclaringType = foundMember.getDeclaringType().resolve(world);
					accumulateTypesInBetween(superType, resolvedDeclaringType, declaringTypes);
					for (ResolvedType declaringType : declaringTypes) {
						JoinPointSignature member = null;
						if (isProxy) {
							if (declaringType.isGenericType() || declaringType.isParameterizedType()) {
								declaringType = declaringType.getRawType();
							}
						}
						member = new JoinPointSignature(foundMember, declaringType);
						discoveredSignatures.add(member); // for next time we are reset
						if (additionalSignatures == Collections.EMPTY_LIST) {
							additionalSignatures = new ArrayList<>();
						}
						additionalSignatures.add(member); // for this time
					}
					// if this was a parameterized type, look in the generic type that backs it too
					if (!isProxy && superType.isParameterizedType() && (foundMember.backingGenericMember != null)) {
						JoinPointSignature member = new JoinPointSignature(foundMember.backingGenericMember,
								foundMember.declaringType.resolve(world));
						discoveredSignatures.add(member); // for next time we are reset
						if (additionalSignatures == Collections.EMPTY_LIST) {
							additionalSignatures = new ArrayList<>();
						}
						additionalSignatures.add(member); // for this time
					}
					if (yetToBeProcessedSuperMembers == null) {
						yetToBeProcessedSuperMembers = new ArrayList<>();
					}
					yetToBeProcessedSuperMembers.add(new SearchPair(foundMember, superType));
					return true;
				} else {
					return findSignaturesFromSupertypes();
				}
			}
		}
		if (yetToBeProcessedSuperMembers != null && !yetToBeProcessedSuperMembers.isEmpty()) {
			SearchPair nextUp = yetToBeProcessedSuperMembers.remove(0);
			firstDefiningType = nextUp.type;
			firstDefiningMember = nextUp.member;
			superTypeIterator = null;
			return findSignaturesFromSupertypes();
		}
		couldBeFurtherAsYetUndiscoveredSignatures = false;
		return false;
	}

	/**
	 * Returns true if the parent member is visible to the child member In the same declaring type this is always true, otherwise if
	 * parent is private it is false.
	 * 
	 * @param childMember
	 * @param parentMember
	 * @return
	 */
	private boolean isVisibleTo(ResolvedMember childMember, ResolvedMember parentMember) {
		if (childMember.getDeclaringType().equals(parentMember.getDeclaringType())) {
			return true;
		}
		if (Modifier.isPrivate(parentMember.getModifiers())) {
			return false;
		} else {
			return true;
		}
	}

	private void warnOnMissingType(ResolvedType missing) {
		if (missing instanceof MissingResolvedTypeWithKnownSignature) {
			// which it should be...
			MissingResolvedTypeWithKnownSignature mrt = (MissingResolvedTypeWithKnownSignature) missing;
			mrt.raiseWarningOnJoinPointSignature(signaturesOfMember.toString());
		}
	}

	private static class SearchPair {
		public ResolvedMember member;
		public ResolvedType type;

		public SearchPair(ResolvedMember member, ResolvedType type) {
			this.member = member;
			this.type = type;
		}
	}

}
