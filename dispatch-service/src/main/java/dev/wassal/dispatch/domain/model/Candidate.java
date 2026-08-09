package dev.wassal.dispatch.domain.model;

/**
 * A courier proposed by the geospatial index, with distance in metres.
 *
 * <p>A candidate is a <em>proposal</em>, never a decision. The index may be stale — the courier may
 * have gone busy since — and that is tolerated by design: the claim is authoritative, so a stale
 * candidate costs one wasted attempt rather than one incorrect assignment.
 */
public record Candidate(CourierId courierId, double distanceMetres) {}
