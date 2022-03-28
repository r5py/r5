package com.conveyal.analysis.util;

import com.conveyal.gtfs.util.GeometryUtil;
import com.conveyal.r5.common.GeometryUtils;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtEncoder;
import com.wdtinc.mapbox_vector_tile.adapt.jts.UserDataKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerParams;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

import java.util.ArrayList;
import java.util.List;

public class MapTile {

    // http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
    // http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Java

    public static final int DEFAULT_TILE_EXTENT = 4096;
    public static final int DEFAULT_TILE_SIZE = 256;

    /**
     * Complex street geometries will be simplified, but the geometry will not deviate from the original by more
     * than this many tile units. These are minuscule at 1/4096 of the tile width or height.
     */
    private static final int LINE_SIMPLIFY_TOLERANCE = 5;

    // Add a buffer to the tile envelope to make sure any artifacts are outside it.
    private static final double TILE_BUFFER_PROPORTION = 0.05;

    public final int zoom;
    public final int x;
    public final int y;
    public final int tileExtent;
    public final int tileSize;

    public final Envelope envelope;

    public MapTile (int zoom, int x, int y) {
        this(zoom, x, y, DEFAULT_TILE_EXTENT, DEFAULT_TILE_SIZE);
    }

    public MapTile (int zoom, int x, int y, int tileExtent, int tileSize) {
        this.zoom = zoom;
        this.x = x;
        this.y = y;
        this.envelope = wgsEnvelope();
        this.tileExtent = tileExtent;
        this.tileSize = tileSize;
    }

//    public static int xTile(double lon, int zoom) {
//        return (int) Math.floor((lon + 180) / 360 * (1 << zoom));
//    }
//
//    public static int yTile(double lat, int zoom) {
//        return (int) Math.floor((1 - Math.log(Math.tan(Math.toRadians(lat))
//                + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1 << zoom));
//    }
//

    public Envelope wgsEnvelope () {
        return wgsEnvelope(zoom, x, y);
    }

    // Create an envelope in WGS84 coordinates for the given map tile numbers.
    public static Envelope wgsEnvelope (final int zoom, final int x, final int y) {
        double north = tile2lat(y, zoom);
        double south = tile2lat(y + 1, zoom);
        double west = tile2lon(x, zoom);
        double east = tile2lon(x + 1, zoom);
        return new Envelope(west, east, south, north);
    }

    public static double tile2lon(int xTile, int zoom) {
        return xTile / Math.pow(2.0, zoom) * 360.0 - 180;
    }

    public static double tile2lat(int yTile, int zoom) {
        double n = Math.PI - (2.0 * Math.PI * yTile) / Math.pow(2.0, zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    public List<Geometry> clipAndSimplifyLinesToTile (List<LineString> lineStrings) {
        List<Geometry> clippedLineStrings = new ArrayList<>(64);
        for (LineString wgsGeometry : lineStrings) {
            Geometry tileGeometry = clipScaleAndSimplify(wgsGeometry);
            if (tileGeometry != null) {
                tileGeometry.setUserData(wgsGeometry.getUserData());
                clippedLineStrings.add(tileGeometry);
            }
        }
        return clippedLineStrings;
    }

    public List<Geometry> projectPointsToTile (List<Point> points) {
        List<Geometry> pointsInTile = new ArrayList<>(64);
        for (Point point : points) {
            Coordinate coordinate = point.getCoordinate();
            if (!envelope.contains(coordinate)) {
                continue;
            }

            Point pointInTile =  GeometryUtils.geometryFactory.createPoint(project(coordinate));
            point.setUserData(point.getUserData());
            pointsInTile.add(pointInTile);
        }
        return pointsInTile;
    }

    /**
     * Utility method reusable in other classes that produce vector tiles. Convert from WGS84 to integer intra-tile
     * coordinates, eliminating points outside the envelope and reducing number of points to keep tile size down.
     *
     * We don't want to include all points of huge geometries when only a small piece of them passes through the tile.
     * This kind of clipping is considered a "standard" but is not technically part of the vector tile specification.
     * See: https://docs.mapbox.com/data/tilesets/guides/vector-tiles-standards/#clipping
     *
     * To handle cases where a geometry passes outside the tile and then back in (e.g. U-shaped sections of routes) we
     * break the geometry into separate pieces where it passes outside the tile.
     *
     * However this is done, we have to make sure the segments end far enough outside the tile that they don't leave
     * artifacts inside the tile, accounting for line width, endcaps etc. so we apply a margin or buffer when deciding
     * which points are outside the tile.
     *
     * We used to use a simpler method where only line segments fully outside the tile were skipped, and originally the
     * "pen" was not even lifted if a line exited the tile and re-entered. However this does not work well with shapes
     * that have very widely spaced points, as it creates huge invisible sections outside the tile clipping area and
     * appears to trigger some coordinate overflow or other problem in the serialization or rendering. It also required
     * manual detection and handling of cases where all points were outside the clip area but the line passed through.
     *
     * Therefore we apply JTS clipping to every feature and sometimes return MultiLineString GeometryCollections
     * which will yield several separate sequences of pen movements in the resulting vector tile. JtsMvt and MvtEncoder
     * don't seem to understand general GeometryCollections, but do interpret MultiLineStrings as expected.
     *
     * @return a Geometry representing the input wgsGeometry in tile units, clipped to the wgsEnvelope with a margin.
     *         The Geometry should always be a LineString or MultiLineString, or null if the geometry has no points
     *         inside the tile.
     */
    public Geometry clipScaleAndSimplify (LineString wgsGeometry) {
        // Add a 5% margin to the envelope make sure any artifacts are outside it.
        // This takes care of the fact that lines have endcaps and widths.
        // Unfortunately this is copying the envelope and adding a margin each time this method is called, so once
        // for each individual geometry within the tile. We can't just add the margin to the envelope before
        // passing it in because we need the true envelope when projecting all the coordinates into tile units.
        // We may want to refactor to process a whole collection of geometries at once to avoid this problem.
        Geometry clippedWgsGeometry;
        {
            Envelope wgsEnvWithMargin = envelope.copy(); // Protective copy before adding margin in place.
            wgsEnvWithMargin.expandBy(
                    envelope.getWidth() * TILE_BUFFER_PROPORTION,
                    envelope.getHeight() * TILE_BUFFER_PROPORTION
            );
            clippedWgsGeometry = GeometryUtil.geometryFactory.toGeometry(wgsEnvWithMargin).intersection(wgsGeometry);
        }
        // Iterate over clipped geometry in case clipping broke the LineString into a MultiLineString
        // or reduced it to nothing (zero elements). Self-intersecting geometries can yield a larger number of elements.
        // For example, DC metro GTFS has notches at stops, which produce a lot of 5-10 element MultiLineStrings.
        List<LineString> outputLineStrings = new ArrayList<>(clippedWgsGeometry.getNumGeometries());
        for (int g = 0; g < clippedWgsGeometry.getNumGeometries(); g += 1) {
            LineString wgsSubGeom = (LineString) clippedWgsGeometry.getGeometryN(g);
            CoordinateSequence wgsCoordinates = wgsSubGeom.getCoordinateSequence();
            if (wgsCoordinates.size() < 2) {
                continue;
            }
            List<Coordinate> tileCoordinates = new ArrayList<>(wgsCoordinates.size());
            for (int c = 0; c < wgsCoordinates.size(); c += 1) {
                tileCoordinates.add(project(wgsCoordinates.getCoordinate(c)));
            }
            LineString tileLineString = GeometryUtils.geometryFactory.createLineString(
                    tileCoordinates.toArray(new Coordinate[0])
            );
            DouglasPeuckerSimplifier simplifier = new DouglasPeuckerSimplifier(tileLineString);
            simplifier.setDistanceTolerance(LINE_SIMPLIFY_TOLERANCE);
            Geometry simplifiedTileGeometry = simplifier.getResultGeometry();
            outputLineStrings.add((LineString) simplifiedTileGeometry);
        }
        // Clipping will frequently leave zero elements.
        if (outputLineStrings.isEmpty()) {
            return null;
        }
        Geometry output;
        if (outputLineStrings.size() == 1) {
            output = outputLineStrings.get(0);
        } else {
            output = GeometryUtils.geometryFactory.createMultiLineString(
                    outputLineStrings.toArray(new LineString[outputLineStrings.size()])
            );
        }
        return output;
    }

    public Coordinate project(Coordinate c) {
        // JtsAdapter.createTileGeom clips and uses full JTS math transform and is much too slow.
        // The following seems sufficient - tile edges should be parallel to lines of latitude and longitude.
        double x = ((c.x - envelope.getMinX()) * tileExtent) / envelope.getWidth();
        double y = ((envelope.getMaxY() - c.y) * tileExtent) / envelope.getHeight();
        return  new Coordinate(x, y);
    }

    public JtsLayer createLayer(String name, List<Geometry> geometries) {
        return new JtsLayer(name, geometries, tileExtent);
    }

    public byte[] encodeLayersToBytes(JtsLayer... layers) {
        JtsMvt vectorTile = new JtsMvt(layers);
        MvtLayerParams mvtLayerParams = new MvtLayerParams(tileSize, tileExtent);
        return MvtEncoder.encode(vectorTile, mvtLayerParams, new UserDataKeyValueMapConverter());
    }
}
