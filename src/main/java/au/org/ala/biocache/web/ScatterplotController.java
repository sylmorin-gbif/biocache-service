package au.org.ala.biocache.web;

import au.org.ala.biocache.dao.SearchDAO;
import au.org.ala.biocache.dao.SearchDAOImpl;
import au.org.ala.biocache.dto.IndexFieldDTO;
import au.org.ala.biocache.dto.SpatialSearchRequestParams;
import au.org.ala.biocache.stream.ScatterplotSearch;
import org.apache.log4j.Logger;
import org.apache.solr.common.SolrDocumentList;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartRenderingInfo;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.encoders.EncoderUtil;
import org.jfree.chart.encoders.ImageFormat;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.DefaultXYDataset;
import org.jfree.ui.RectangleEdge;
import org.springframework.aop.framework.Advised;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This controller is responsible for providing basic scatterplot services.
 *
 * Basic scatterplot is
 * - occurrences, standard biocache query
 * - x, numerical stored value field
 * - y, numerical stored value field
 * - height, integer default 256
 * - width, integer default 256
 * - title, string default query-display-name
 * - pointcolour, colour as RGB string like FF0000 for red, default 0000FF
 * - pointradius, double default 3
 *
 */
@Controller
public class ScatterplotController {

    private static Logger logger = Logger.getLogger(ScatterplotController.class);

    private final static int PAGE_SIZE = 100000000;
    private final static String DEFAULT_SCATTERPLOT_TITLE = " ";
    private final static String DEFAULT_SCATTERPLOT_HEIGHT = "256";
    private final static String DEFAULT_SCATTERPLOT_WIDTH = "256";
    private final static String DEFAULT_SCATTERPLOT_POINTCOLOUR = "0000FF";
    private final static String DEFAULT_SCATTERPLOT_POINTRADIUS = "3";
    private final static String [] VALID_DATATYPES = {"float","double","int","long","tfloat","tdouble","tint","tlong"};

    @Inject
    protected SearchDAO searchDAO;

    @RequestMapping(value = {"/scatterplot"}, method = RequestMethod.GET)
    public void scatterplot(SpatialSearchRequestParams requestParams,
                            @RequestParam(value = "x", required = true) String x,
                            @RequestParam(value = "y", required = true) String y,
                            @RequestParam(value = "height", required = false, defaultValue=DEFAULT_SCATTERPLOT_HEIGHT) Integer height,
                            @RequestParam(value = "width", required = false, defaultValue=DEFAULT_SCATTERPLOT_WIDTH) Integer width,
                            @RequestParam(value = "title", required = false, defaultValue=DEFAULT_SCATTERPLOT_TITLE) String title,
                            @RequestParam(value = "pointcolour", required = false, defaultValue=DEFAULT_SCATTERPLOT_POINTCOLOUR) String pointcolour,
                            @RequestParam(value = "pointradius", required = false, defaultValue = DEFAULT_SCATTERPLOT_POINTRADIUS) Double pointradius,
                            HttpServletResponse response) throws Exception {
        JFreeChart jChart = makeScatterplot(requestParams, x, y, title, pointcolour, pointradius);

        //produce image
        ChartRenderingInfo chartRenderingInfo = new ChartRenderingInfo();
        BufferedImage bi = jChart.createBufferedImage(width, height, BufferedImage.TRANSLUCENT, chartRenderingInfo);
        byte[] bytes = EncoderUtil.encode(bi, ImageFormat.PNG, true);

        //output image
        response.setContentType("image/png");

        try {
            response.getOutputStream().write(bytes);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @RequestMapping(value = {"/scatterplot/point"}, method = RequestMethod.GET)
    public Map scatterplotPointInfo(SpatialSearchRequestParams requestParams,
                            @RequestParam(value = "x", required = true) String x,
                            @RequestParam(value = "y", required = true) String y,
                            @RequestParam(value = "height", required = false, defaultValue=DEFAULT_SCATTERPLOT_HEIGHT) Integer height,
                            @RequestParam(value = "width", required = false, defaultValue=DEFAULT_SCATTERPLOT_WIDTH) Integer width,
                            @RequestParam(value = "title", required = false, defaultValue=DEFAULT_SCATTERPLOT_TITLE) String title,
                            @RequestParam(value = "pointx1", required = true) Integer pointx1,
                            @RequestParam(value = "pointy1", required = true) Integer pointy1,
                            @RequestParam(value = "pointx2", required = true) Integer pointx2,
                            @RequestParam(value = "pointy2", required = true) Integer pointy2) throws Exception {

        JFreeChart jChart = makeScatterplot(requestParams, x, y, title, "000000", 1.0);

        //produce image
        ChartRenderingInfo chartRenderingInfo = new ChartRenderingInfo();
        BufferedImage bi = jChart.createBufferedImage(width, height, BufferedImage.TRANSLUCENT, chartRenderingInfo);

        XYPlot plot = (XYPlot) jChart.getPlot();

        //identify point range across x and y
        double tx1 = plot.getRangeAxis().java2DToValue(pointx1, chartRenderingInfo.getPlotInfo().getDataArea(), RectangleEdge.BOTTOM);
        double tx2 = plot.getRangeAxis().java2DToValue(pointx2, chartRenderingInfo.getPlotInfo().getDataArea(), RectangleEdge.BOTTOM);
        double ty1 = plot.getDomainAxis().java2DToValue(pointy1, chartRenderingInfo.getPlotInfo().getDataArea(), RectangleEdge.LEFT);
        double ty2 = plot.getDomainAxis().java2DToValue(pointy2, chartRenderingInfo.getPlotInfo().getDataArea(), RectangleEdge.LEFT);
        double x1 = Math.min(tx1, tx2);
        double x2 = Math.max(tx1, tx2);
        double y1 = Math.min(ty1, ty2);
        double y2 = Math.max(ty1, ty2);

        Map map = new HashMap();
        map.put("xaxis_pixel_selection",new int[] {pointx1, pointx2});
        map.put("yaxis_pixel_selection",new int[] {pointy1, pointy2});
        map.put("xaxis",x);
        map.put("yaxis",y);
        map.put("xaxis_range",new double[]{x1, x2});
        map.put("yaxis_range",new double[]{y1, y2});

        return map;

        /*
        //add new fqs
        String [] fqs_old = requestParams.getFq();
        String [] fqs_new = new String[fqs_old.length + 2];
        System.arraycopy(fqs_old,0,fqs_new,0,fqs_old.length);
        fqs_new[fqs_old.length] = x + ":[" + x1 + " TO " + x2 + "]";
        fqs_new[fqs_old.length + 1] = y + ":[" + y1 + " TO " + y2 + "]";
        requestParams.setFq(fqs_new);
        return searchDao.findByFulltextSpatialQuery(requestParams, null);
        */
    }

    JFreeChart makeScatterplot(SpatialSearchRequestParams requestParams, String x, String y
        , String title, String pointcolour, Double pointradius) throws Exception {
        //verify x and y are numerical and stored
        String displayNameX = null;
        String displayNameY = null;
        List<String> validDatatypes = Arrays.asList(VALID_DATATYPES);
        Set<IndexFieldDTO> indexedFields = searchDAO.getIndexedFields();
        
        Exception toThrowX = null;
        
        for (IndexFieldDTO xField : indexedFields) {
            if(xField.getName().equals(x)) {
                if (!validDatatypes.contains(xField.getDataType() )) {
                    toThrowX = new Exception("Invalid datatype: " + xField.getDataType() + " for x: " + x, toThrowX);
                } else if (!xField.isDocvalue()) {
                    toThrowX = new Exception("Cannot use x: " + x + ".  It is not a docvalue field.", toThrowX);
                }
                else {
                    displayNameX = xField.getDescription();
                    break;
                }
            }
        }
        
        if(displayNameX == null) {
            throw new Exception("Unknown, unsupported datatype, or not stored, value for x: " + x, toThrowX);
        }
        
        Exception toThrowY = null;
        
        for (IndexFieldDTO yField : indexedFields) {
            if(yField.getName().equals(y)) {
                if (!validDatatypes.contains(yField.getDataType() )) {
                    toThrowY = new Exception("Invalid datatype: " + yField.getDataType() + " for y: " + y, toThrowY);
                } else if (!yField.isDocvalue()) {
                    toThrowY = new Exception("Cannot use y: " + y + ".  It is not a docvalue field.", toThrowY);
                }
                else {
                    displayNameY = yField.getDescription();
                    break;
                }
            }
        }
        
        if(displayNameY == null) {
            throw new Exception("Unknown, unsupported datatype, or not stored, value for y: " + y, toThrowY);
        }

        long start = System.currentTimeMillis();
        requestParams.setPageSize(0);
        requestParams.setFacet(false);
        SolrDocumentList sdl = searchDAO.findByFulltext(requestParams);
        int size = (int) sdl.getNumFound();

        requestParams.setPageSize(PAGE_SIZE);
        requestParams.setFl(x + "," + y);
        requestParams.setSort(x);
        requestParams.setDir("asc");

        double [][] data = new double[2][size];
        AtomicInteger count = new AtomicInteger(0); // need to get the return value
        ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).streamingQuery(requestParams, null, new ScatterplotSearch(data, x, y, count), null);

        // resize data
        double[] a = data[0];
        double[] b = data[1];

        data[0] = new double[count.intValue()];
        data[1] = new double[count.intValue()];

        System.arraycopy(a, 0, data[0], 0, count.intValue());
        System.arraycopy(b, 0, data[1], 0, count.intValue());

        long end = System.currentTimeMillis();
        System.out.println((end - start) + "ms");

        if (count.intValue() == 0) {
            throw new Exception("valid records found for these input parameters");
        }

        //create dataset
        DefaultXYDataset xyDataset = new DefaultXYDataset();
        xyDataset.addSeries("series", data);

        //create chart
        JFreeChart jChart = ChartFactory.createScatterPlot(
                title.equals(" ")?requestParams.getDisplayString():title //chart display name
                , displayNameY //x-axis display name
                , displayNameX //y-axis display name
                , xyDataset
                , PlotOrientation.HORIZONTAL, false, false, false);
        jChart.setBackgroundPaint(Color.white);

        //styling
        XYPlot plot = (XYPlot) jChart.getPlot();
        Font axisfont = new Font("Arial", Font.PLAIN, 10);
        Font titlefont = new Font("Arial", Font.BOLD, 11);
        plot.getDomainAxis().setLabelFont(axisfont);
        plot.getDomainAxis().setTickLabelFont(axisfont);
        plot.getRangeAxis().setLabelFont(axisfont);
        plot.getRangeAxis().setTickLabelFont(axisfont);
        plot.setBackgroundPaint(new Color(220, 220, 220));
        jChart.getTitle().setFont(titlefont);

        //point shape and colour
        Color c = new Color(Integer.parseInt(pointcolour, 16));
        plot.getRenderer().setSeriesPaint(0, c);
        plot.getRenderer().setSeriesShape(0, new Ellipse2D.Double(-pointradius, -pointradius, pointradius*2, pointradius*2));

        return jChart;
    }
}
