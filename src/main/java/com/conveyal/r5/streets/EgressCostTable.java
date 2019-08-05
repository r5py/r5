package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.util.LambdaCounter;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.conveyal.r5.transit.TransitLayer.WALK_DISTANCE_LIMIT_METERS;

/**
 * This holds pre-calculated distances from stops to points (and vice versa)
 */
public class EgressCostTable {

    private static final Logger LOG = LoggerFactory.getLogger(EgressCostTable.class);

    // CONSTANTS

    public static final int BICYCLE_DISTANCE_LINKING_LIMIT_METERS = 5000;

    public static final int CAR_TIME_LINKING_LIMIT_SECONDS = 30 * 60;

    public static final int MAX_CAR_SPEED_METERS_PER_SECOND = 44; // ~160 kilometers per hour

    /** The linkage from which these cost tables are built. */
    public final LinkedPointSet linkedPointSet;

    /**
     * By default, linkage costs are distances (between stops and pointset points). For modes where speeds vary
     * by link, it doesn't make sense to store distances, so we store times.
     * TODO perhaps we should leave this uninitialized, only initializing once the linkage mode is known around L510-540.
     * We would then fail fast on any programming errors that don't set or copy the cost unit.
     */
    public final StreetRouter.State.RoutingVariable linkageCostUnit;

    /** For each transit stop, the distances (or times) to nearby PointSet points as packed (point_index, distance)
     * pairs. */
    public final List<int[]> stopToPointLinkageCostTables;

    /**
     * For each pointset point, the stops reachable without using transit, as a map from StopID to distance. For walk
     * and bike, distance is in millimeters; for car, distance is actually time in seconds. Inverted version of
     * stopToPointLinkageCostTables. This is used in PerTargetPropagator to find all the stops near a particular point
     * (grid cell) so we can perform propagation to that grid cell only. We only retain a few percentiles of travel
     * time at each target cell, so doing one cell at a time allows us to keep the output size within reason.
     * TODO why is this transient? When is this serialized? Maybe just because it's bigger and has more references than the source table?
     */
    public transient List<TIntIntMap> pointToStopLinkageCostTables;

    /**
     * For each transit stop in the associated TransportNetwork, make a table of distances to nearby points in this
     * PointSet.
     * At one point we experimented with doing the entire search from the transit stops all the way up to the points
     * within this method. However, that takes too long when switching PointSets. So we pre-cache distances to all
     * street vertices in the TransitNetwork, and then just extend those tables to the points in the PointSet.
     * This is one of the slowest steps in working with a new scenario. It takes about 50 seconds to link 400000 points.
     * The run time is not shocking when you consider the complexity of the operation: there are nStops * nPoints
     * iterations, which is 8000 * 400000 in the example case. This means 6 msec per transit stop, or 2e-8 sec per point
     * iteration, which is not horribly slow. There are just too many iterations.
     *
     * It would be possible to pull out pure (even static) functions to set these final fields.
     * Or make some factory methods or classes which produce immutable tables.
     */
    public EgressCostTable(LinkedPointSet linkedPointSet, LinkedPointSet baseLinkage) {

        this.linkedPointSet = linkedPointSet;

        if (linkedPointSet.streetMode == StreetMode.CAR) {
            this.linkageCostUnit = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        } else {
            this.linkageCostUnit = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        }
        if (baseLinkage != null && this.linkageCostUnit != baseLinkage.egressCostTable.linkageCostUnit) {
            throw new AssertionError("The base linkage's cost table is in the wrong units.");
        }

        // The regions within which we want to link points to edges, then connect transit stops to points.
        // Null means relink and rebuild everything. This will be constrained below if a base linkage was supplied, to
        // encompass only areas near changed or created edges.
        //
        // Only build trees for stops inside this geometry in FIXED POINT DEGREES, leaving all the others alone.
        // If null, build trees for all stops.
        final Geometry rebuildZone;

        final StreetMode streetMode = linkedPointSet.streetMode;

        /**
         * Limit to use when building linkageCostTables, re-calculated for different streetModes as needed, using the
         * constants specified above. The value should be larger than any per-leg street mode limits that can be requested
         * in the UI.
         * FIXME we should leave this uninitialized, only initializing once the linkage mode is known.
         * We would then fail fast on any programming errors that don't set or copy the limit.
         * FIXME this appears to be used only in the constructor, should we really save it in an instance field? Maybe, just for debugging.
         */
        final int linkingDistanceLimitMeters;

        if (baseLinkage != null) {
            // TODO perhaps create alternate constructor of EgressCostTable which copies an existing one
            // TODO We need to determine which points to re-link and which stops should have their stop-to-point tables re-built.
            // TODO check where and how we re-link to streets split/modified by the scenario.
            // This should be all the points within the (bird-fly) linking radius of any modified edge.
            // The stop-to-vertex trees should already be rebuilt elsewhere when applying the scenario.
            // This should be all the stops within the (network or bird-fly) tree radius of any modified edge, including
            // any new stops that were created by the scenario (which will naturally fall within this distance).
            // And build trees from stops to points.
            // Even though currently the only changes to the street network are re-splitting edges to connect new
            // transit stops, we still need to re-link points and rebuild stop trees (both the trees to the vertices
            // and the trees to the points, because some existing stop-to-vertex trees might not include new splitter
            // vertices).
            // FIXME wait why are we only calculating a distance limit when a base linkage is supplied? Because otherwise we link all points and don't need a radius.
            if (streetMode == StreetMode.WALK) {
                linkingDistanceLimitMeters = WALK_DISTANCE_LIMIT_METERS;
            } else if (streetMode == StreetMode.BICYCLE) {
                linkingDistanceLimitMeters = BICYCLE_DISTANCE_LINKING_LIMIT_METERS;
            } else if (streetMode == StreetMode.CAR) {
                linkingDistanceLimitMeters = CAR_TIME_LINKING_LIMIT_SECONDS * MAX_CAR_SPEED_METERS_PER_SECOND;
            } else {
                throw new UnsupportedOperationException("Unrecognized streetMode");
            }
            rebuildZone = linkedPointSet.streetLayer.scenarioEdgesBoundingGeometry(linkingDistanceLimitMeters);
        } else {
            rebuildZone = null; // rebuild everything.
            linkingDistanceLimitMeters = WALK_DISTANCE_LIMIT_METERS; // TODO check pre-refactor code, isn't this assuming WALK mode unless there's a base linkage?
        }

         LOG.info("Creating linkage cost tables from each transit stop to PointSet points.");
         // FIXME using a spatial index is wasting a lot of memory and not needed for gridded pointsets - overload for gridded and freeform PointSets
         // We could just do this in the method that uses the pointset spatial index (extendDistanceTableToPoints) but that's called in a tight loop.
         linkedPointSet.pointSet.createSpatialIndexAsNeeded();

        if (rebuildZone != null) {
            LOG.info("Selectively computing tables for only those stops that might be affected by the scenario.");
        }
        TransitLayer transitLayer = linkedPointSet.streetLayer.parentNetwork.transitLayer;
        int nStops = transitLayer.getStopCount();

        int logFrequency = 1000;
        if (streetMode == StreetMode.CAR) {
            // Log more often because car searches are very slow.
            logFrequency = 100;
        }
        LambdaCounter counter = new LambdaCounter(LOG, nStops, logFrequency,
                "Computed distances to PointSet points from {} of {} transit stops.");
        // Create a distance table from each transit stop to the points in this PointSet in parallel.
        // Each table is a flattened 2D array. Two values for each point reachable from this stop: (pointIndex, cost)
        // When applying a scenario, keep the existing distance table for those stops that could not be affected.
        // TODO factor out the function that computes a cost table for one stop.
        stopToPointLinkageCostTables = IntStream.range(0, nStops).parallel().mapToObj(stopIndex -> {
            Point stopPoint = transitLayer.getJTSPointForStopFixed(stopIndex);
            // If the stop is not linked to the street network, it should have no distance table.
            if (stopPoint == null) return null;
            if (rebuildZone != null && !rebuildZone.contains(stopPoint)) {
                // This cannot be affected by the scenario. Return the existing distance table.
                // All new stops created by a scenario should be inside the relink zone, so
                // all stops outside the relink zone should already have a distance table entry.
                // TODO having a rebuild zone always implies there is a baseLinkage? If it's possible to not have a base linkage, this should return null.
                // All stops created by the scenario should by definition be inside the relink zone.
                // This conditional is handling stops outside the relink zone, which should always have existed before
                // scenario application. Therefore they should be present in the base linkage cost tables.
                return baseLinkage.egressCostTable.stopToPointLinkageCostTables.get(stopIndex);
            }

            counter.increment();
            Envelope envelopeAroundStop = stopPoint.getEnvelopeInternal();
            GeometryUtils.expandEnvelopeFixed(envelopeAroundStop, linkingDistanceLimitMeters);

            if (streetMode == StreetMode.WALK) {
                // Walking distances from stops to street vertices are saved in the TransitLayer.
                // Get the pre-computed walking distance table from the stop to the street vertices,
                // then extend that table out from the street vertices to the points in this PointSet.
                // TODO reuse the code that computes the walk tables at TransitLayer.buildOneDistanceTable() rather than duplicating it below for other modes.
                TIntIntMap distanceTableToVertices = transitLayer.stopToVertexDistanceTables.get(stopIndex);
                return distanceTableToVertices == null ? null :
                        linkedPointSet.extendDistanceTableToPoints(distanceTableToVertices, envelopeAroundStop);
            } else {

                StreetRouter sr = new StreetRouter(transitLayer.parentNetwork.streetLayer);
                sr.streetMode = streetMode;
                int vertexId = transitLayer.streetVertexForStop.get(stopIndex);
                if (vertexId < 0) {
                    LOG.warn("Stop unlinked, cannot build distance table: {}", stopIndex);
                    return null;
                }
                // TODO setting the origin point of the router to the stop vertex does not work.
                // This is probably because link edges do not allow car traversal. We could traverse them.
                // As a stopgap we perform car linking at the geographic coordinate of the stop.
                // sr.setOrigin(vertexId);
                VertexStore.Vertex vertex = linkedPointSet.streetLayer.vertexStore.getCursor(vertexId);
                sr.setOrigin(vertex.getLat(), vertex.getLon());

                if (streetMode == StreetMode.BICYCLE) {
                    sr.distanceLimitMeters = linkingDistanceLimitMeters;
                    sr.quantityToMinimize = linkageCostUnit;
                    sr.route();
                    return linkedPointSet.extendDistanceTableToPoints(sr.getReachedVertices(), envelopeAroundStop);
                } else if (streetMode == StreetMode.CAR) {
                    // The speeds for Walk and Bicycle can be specified in an analysis request, so it makes sense above to
                    // store distances and apply the requested speed. In contrast, car speeds vary by link and cannot be
                    // set in analysis requests, so it makes sense to use seconds directly as the linkage cost.
                    // TODO confirm this works as expected when modifications can affect street layer.
                    sr.timeLimitSeconds = CAR_TIME_LINKING_LIMIT_SECONDS;
                    sr.quantityToMinimize = linkageCostUnit;
                    sr.route();
                    // TODO optimization: We probably shouldn't evaluate at every point in this LinkedPointSet in case it's much bigger than the driving radius.
                    PointSetTimes driveTimesToAllPoints = linkedPointSet.eval(
                            sr::getTravelTimeToVertex,
                            null,
                            LinkedPointSet.OFF_STREET_SPEED_MILLIMETERS_PER_SECOND
                    );
                    // TODO optimization: should we make spatial index visit() method public to avoid copying results?
                    TIntList packedDriveTimes = new TIntArrayList();
                    for (int p = 0; p < driveTimesToAllPoints.size(); p++) {
                        int driveTimeToPoint = driveTimesToAllPoints.getTravelTimeToPoint(p);
                        if (driveTimeToPoint != Integer.MAX_VALUE) {
                            packedDriveTimes.add(p);
                            packedDriveTimes.add(driveTimeToPoint);
                        }
                    }
                    if (packedDriveTimes.isEmpty()) {
                        return null;
                    } else {
                        return packedDriveTimes.toArray();
                    }
                } else {
                    throw new UnsupportedOperationException("Tried to link a pointset with an unsupported street mode");
                }
            }
        }).collect(Collectors.toList());
        counter.done();

        // Transpose the table, making the one that's actually used in routing.
        makePointToStopTables();
    }

    /**
     * Constructor for copying a strict sub-geographic area, with no rebuilding of any linkages or tables.
     * Interestingly this has exactly the same signature as the other constructor, which makes me wonder if they can
     * be combined into a single constructor.
     */
    public EgressCostTable (LinkedPointSet linkedPointSet, LinkedPointSet baseLinkage) {

        this.linkedPointSet = linkedPointSet;
        this.linkageCostUnit = x;

        WebMercatorGridPointSet superGrid = (WebMercatorGridPointSet) baseLinkage.pointSet;
        WebMercatorGridPointSet subGrid = (WebMercatorGridPointSet) linkedPointSet.pointSet;

        // For each transit stop, we have a table of costs to reach pointset points (or null if none can be reached).
        // If such tables have already been built for the source linkage, copy them and crop to a smaller rectangle as
        // needed (as was done for the basic linkage information above).
        stopToPointLinkageCostTables = baseLinkage.egressCostTable.stopToPointLinkageCostTables.stream()
                .map(distanceTable -> {
                    if (distanceTable == null) {
                        // If the stop could not reach any points in the super-pointset,
                        // it cannot reach any points in this sub-pointset.
                        return null;
                    }
                    TIntList newDistanceTable = new TIntArrayList();
                    for (int i = 0; i < distanceTable.length; i += 2) {
                        int targetInSuperLinkage = distanceTable[i];
                        int distance = distanceTable[i + 1];

                        int superX = targetInSuperLinkage % superGrid.width;
                        int superY = targetInSuperLinkage / superGrid.width;

                        int subX = superX + superGrid.west - subGrid.west;
                        int subY = superY + superGrid.north - subGrid.north;

                        // Only retain distance information for points that fall within this sub-grid.
                        if (subX >= 0 && subX < subGrid.width && subY >= 0 && subY < subGrid.height) {
                            int targetInSubLinkage = subY * subGrid.width + subX;
                            newDistanceTable.add(targetInSubLinkage);
                            newDistanceTable.add(distance); // distance to target does not change when we crop the pointset
                        }
                    }
                    if (newDistanceTable.isEmpty()) {
                        // No points in the sub-pointset can be reached from this transit stop.
                        return null;
                    }
                    return newDistanceTable.toArray();
                })
                .collect(Collectors.toList());

    }

    /**
     * This method transposes the cost tables, yielding impedance from each point back to all stops that can reach it.
     * The original calculation is performed from each stop out to the points it can reach.
     * TODO Can we throw away the original stop -> point tables? That would save a lot of memory.
     * TODO The target field is now final and transient. We should decide whether this is rebuilding a transient field, or a persistent field.
     */
    public void makePointToStopTables () {
        TIntIntMap[] result = new TIntIntMap[linkedPointSet.size()];
        for (int stop = 0; stop < stopToPointLinkageCostTables.size(); stop++) {
            int[] stopToPointDistanceTable = stopToPointLinkageCostTables.get(stop);
            if (stopToPointDistanceTable == null) continue;

            for (int idx = 0; idx < stopToPointDistanceTable.length; idx += 2) {
                int point = stopToPointDistanceTable[idx];
                int distance = stopToPointDistanceTable[idx + 1];
                if (result[point] == null) result[point] = new TIntIntHashMap();
                result[point].put(stop, distance);
            }
        }
        this.pointToStopLinkageCostTables = Arrays.asList(result);
    }

}
