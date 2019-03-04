package au.org.ala.biocache.stream;

import au.org.ala.biocache.dto.OccurrencePoint;
import au.org.ala.biocache.dto.PointType;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.io.Tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PointsFacet implements ProcessInterface {
    private final static Logger logger = Logger.getLogger(PointsFacet.class);

    List<OccurrencePoint> points;
    PointType pointType;

    public PointsFacet(List<OccurrencePoint> points, PointType pointType) {
        this.points = points;
        this.pointType = pointType;
    }

    @Override
    public boolean process(Tuple t) {
        long count = 0;
        String coordString = "";
        for (Object value : t.getMap().values()) {
            if (value instanceof String) {
                coordString = (String) value;
            } else {
                count = (Long) value;
            }
        }

        String[] pointsDelimited = org.apache.commons.lang.StringUtils.split(coordString, ',');
        List<Float> coords = new ArrayList<Float>();

        for (String coord : pointsDelimited) {
            try {
                Float decimalCoord = Float.parseFloat(coord);
                coords.add(decimalCoord);
            } catch (NumberFormatException numberFormatException) {
                logger.warn("Error parsing Float for Lat/Long: " + numberFormatException.getMessage(), numberFormatException);
            }
        }

        if (coords.size() == 2) {
            Collections.reverse(coords); // must be long, lat order
            OccurrencePoint point = new OccurrencePoint(pointType);
            point.setCoordinates(coords);
            point.setCount(count);
            points.add(point);
        }


        return true;
    }

    @Override
    public boolean flush() {
        return true;
    }
}
