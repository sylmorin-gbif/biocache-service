package au.org.ala.biocache.stream;

import au.org.ala.biocache.util.ImgObj;
import au.org.ala.biocache.util.SpatialUtils;
import au.org.ala.biocache.util.WmsEnv;
import au.org.ala.biocache.web.WMSController;
import org.apache.solr.client.solrj.io.Tuple;
import org.geotools.geometry.GeneralDirectPosition;
import org.opengis.geometry.DirectPosition;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.TransformException;

import java.awt.*;

public class DrawHighlight implements ProcessInterface {

    ImgObj imgObj;
    WmsEnv vars;
    double[] tilebbox;
    int width;
    int height;
    CoordinateOperation transformFrom4326;
    WMSController wmsController;

    double top;
    double bottom;
    double left;
    double right;
    int highightRadius;
    int highlightWidth;

    public DrawHighlight(ImgObj imgObj, WmsEnv vars, double[] tilebbox, int width, int height, CoordinateOperation transformFrom4326, WMSController wmsController) {
        this.imgObj = imgObj;
        this.vars = vars;
        this.tilebbox = tilebbox;
        this.width = width;
        this.height = height;
        this.transformFrom4326 = transformFrom4326;
        this.wmsController = wmsController;

        //for image scaling
        top = tilebbox[3];
        bottom = tilebbox[1];
        left = tilebbox[0];
        right = tilebbox[2];

        highightRadius = vars.size + wmsController.HIGHLIGHT_RADIUS;
        highlightWidth = highightRadius * 2;

        imgObj.g.setStroke(new BasicStroke(2));
        imgObj.g.setColor(new Color(255, 0, 0, 255));
    }

    @Override
    public boolean process(Tuple t) {
        for (Object o : t.getMap().values()) {
            if (o instanceof String) {
                //extract lat lng
                String[] lat_lng = ((String) o).split(",");

                try {
                    float lng = Float.parseFloat(lat_lng[1]);
                    float lat = Float.parseFloat(lat_lng[0]);

                    GeneralDirectPosition sourceCoords = new GeneralDirectPosition(lng, lat);
                    DirectPosition targetCoords = transformFrom4326.getMathTransform().transform(sourceCoords, null);
                    int x = SpatialUtils.scaleLongitudeForImage(targetCoords.getOrdinate(0), left, right, width);
                    int y = SpatialUtils.scaleLatitudeForImage(targetCoords.getOrdinate(1), top, bottom, height);

                    imgObj.g.drawOval(x - highightRadius, y - highightRadius, highlightWidth, highlightWidth);
                } catch (MismatchedDimensionException e) {
                } catch (TransformException e) {
                    // failure to transform a coordinate will result in it not rendering
                }
            }
        }

        return true;
    }

    @Override
    public boolean flush() {
        return true;
    }
}
