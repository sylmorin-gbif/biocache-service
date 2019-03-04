/**************************************************************************
 *  Copyright (C) 2017 Atlas of Living Australia
 *  All Rights Reserved.
 *
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.web;

import au.org.ala.biocache.dao.QidCacheDAO;
import au.org.ala.biocache.dao.SearchDAO;
import au.org.ala.biocache.dao.SearchDAOImpl;
import au.org.ala.biocache.dao.TaxonDAO;
import au.org.ala.biocache.dto.*;
import au.org.ala.biocache.model.Qid;
import au.org.ala.biocache.stream.DrawHighlight;
import au.org.ala.biocache.stream.StreamAsCSV;
import au.org.ala.biocache.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.geotools.geometry.GeneralDirectPosition;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.DefaultCoordinateOperationFactory;
import org.opengis.geometry.DirectPosition;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.TransformException;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.ehcache.EhCacheManagerFactoryBean;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This controller provides mapping services which include WMS services. Includes support for:
 *
 * <ul>
 *    <li>GetCapabilities</li>
 *    <li>GetMap</li>
 *    <li>GetFeatureInfo</li>
 *    <li>GetMetadata</li>
 * </ul>
 */
@Controller
public class WMSController extends AbstractSecureController{

    /**
     * webportal results limit
     */
    private final int DEFAULT_PAGE_SIZE = 1000000;

    @Value("${wms.colour:0x00000000}")
    private int DEFAULT_COLOUR;
    /**
     * webportal image max pixel count
     */
    @Value("${wms.image.pixel.count:36000000}")
    private int MAX_IMAGE_PIXEL_COUNT; //this is slightly larger than 600dpi A4
    /**
     * legend limits
     */
    private final String NULL_NAME = "Unknown";
    /**
     * max uncertainty mappable in m
     */
    @Value("${wms.uncertainty.max:30000}")
    private double MAX_UNCERTAINTY;
    /**
     * add pixel radius for wms highlight circles
     */
    @Value("${wms.highlight.radius:3}")
    public static int HIGHLIGHT_RADIUS;
    /**
     * Global wms cache enable. WMS requests can disable adding to the cache using CACHE=off. WMS CACHE=off will still
     * read from the cache.
     */
    @Value("${wms.cache.enabled:true}")
    private boolean wmsCacheEnabled;
    /**
     * Logger initialisation
     */
    private final static Logger logger = Logger.getLogger(WMSController.class);
    /**
     * Fulltext search DAO
     */
    @Inject
    protected SearchDAO searchDAO;
    @Inject
    protected QueryFormatUtils queryFormatUtils;
    @Inject
    protected TaxonDAO taxonDAO;
    @Inject
    protected SearchUtils searchUtils;
    @Inject
    protected QidCacheDAO qidCacheDAO;
    @Inject
    protected WMSCache wmsCache;

    /**
     * Load a smaller 256x256 png than java.image produces
     */
    final static byte[] blankImageBytes;

    @Value("${webservices.root:https://biocache.ala.org.au/ws}")
    protected String baseWsUrl;

    @Value("${biocache.ui.url:https://biocache.ala.org.au}")
    protected String baseUiUrl;

    @Value("${geoserver.url:http://spatial.ala.org.au/geoserver}")
    protected String geoserverUrl;

    @Value("${organizationName:Atlas of Living Australia}")
    protected String organizationName;
    @Value("${orgCity:Canberra}")
    protected String orgCity;
    @Value("${orgStateProvince:ACT}")
    protected String orgStateProvince;
    @Value("${orgPostcode:2601}")
    protected String orgPostcode;
    @Value("${orgCountry:Australia}")
    protected String orgCountry;
    @Value("${orgPhone:+61 (0) 2 6246 4400}")
    protected String orgPhone;
    @Value("${orgFax:+61 (0) 2 6246 4400}")
    protected String orgFax;
    @Value("${orgEmail:support@ala.org.au}")
    protected String orgEmail;

    @Value("${service.bie.ws.url:https://bie.ala.org.au/ws}")
    protected String bieWebService;

    @Value("${service.bie.ui.url:https://bie.ala.org.au}")
    protected String bieUiUrl;

    @Value("${wms.capabilities.focus:latitude:[-90 TO 90] AND longitude:[-180 TO 180]}")
    protected String limitToFocusValue;

    /**
     * Limit WKT complexity to reduce index query time for qids.
     */
    @Value("${qid.wkt.maxPoints:5000}")
    private int maxWktPoints;

    /**
     * Threshold for caching a whole PointType for a query or only caching the current bounding box.
     */
    @Value("${wms.cache.maxLayerPoints:100000}")
    private int wmsCacheMaxLayerPoints;

    /**
     * Occurrence count where < uses pivot and > uses facet for retrieving points. Can be fine tuned with
     * multiple queries and comparing DEBUG *
     */
    @Value("${wms.facetPivotCutoff:2000}")
    private int wmsFacetPivotCutoff;

    /**
     * The public or private value to use in the Cache-Control HTTP header for WMS tiles. Defaults to public
     */
    @Value("${wms.cache.cachecontrol.publicorprivate:public}")
    private String wmsCacheControlHeaderPublicOrPrivate;

    /**
     * The max-age value to use in the Cache-Control HTTP header for WMS tiles. Defaults to 86400, equivalent to 1 day
     */
    @Value("${wms.cache.cachecontrol.maxage:86400}")
    private String wmsCacheControlHeaderMaxAge;

    private final AtomicReference<String> wmsETag = new AtomicReference<String>(UUID.randomUUID().toString());

    //Stores query hashes + occurrence counts, and, query hashes + pointType + point counts
    private LRUMap countsCache = new LRUMap(10000);
    private final Object countLock = new Object();

    @Inject
    protected WMSOSGridController wmsosGridController;

    static {
        // cache blank image bytes
        byte[] b = null;
        try (RandomAccessFile raf = new RandomAccessFile(WMSController.class.getResource("/blank.png").getFile(), "r");) {
            b = new byte[(int) raf.length()];
            raf.read(b);
        } catch (Exception e) {
            logger.error("Unable to open blank image file", e);
        }
        blankImageBytes = b;

        // configure geotools to use x/y order for SRS operations
        System.setProperty("org.geotools.referencing.forceXY", "true");
    }

    @Inject
    EhCacheManagerFactoryBean cacheManager;

    @RequestMapping(value = {"/webportal/params", "/mapping/params"}, method = RequestMethod.POST)
    public void storeParams(SpatialSearchRequestParams requestParams,
                            @RequestParam(value = "bbox", required = false, defaultValue = "false") String bbox,
                            @RequestParam(value = "title", required = false) String title,
                            @RequestParam(value = "maxage", required = false, defaultValue = "-1") Long maxage,
                            @RequestParam(value = "source", required = false) String source,
                            HttpServletResponse response) throws Exception {

        //set default values for parameters not stored in the qid.
        requestParams.setFl("");
        requestParams.setFacets(new String[] {});
        requestParams.setStart(0);
        requestParams.setFacet(false);
        requestParams.setFlimit(0);
        requestParams.setPageSize(0);
        requestParams.setSort("score");
        requestParams.setDir("asc");
        requestParams.setFoffset(0);
        requestParams.setFprefix("");
        requestParams.setFsort("");
        requestParams.setIncludeMultivalues(false);

        //move qc to fq
        if (StringUtils.isNotEmpty(requestParams.getQc())) {
            queryFormatUtils.addFqs(new String[]{requestParams.getQc()}, requestParams);
            requestParams.setQc("");
        }

        //move lat/lon/radius to fq
        if (requestParams.getLat() != null) {
            String fq = queryFormatUtils.buildSpatialQueryString(requestParams);
            queryFormatUtils.addFqs(new String[]{fq}, requestParams);
            requestParams.setLat(null);
            requestParams.setLon(null);
            requestParams.setRadius(null);
        }

        String qid = qidCacheDAO.generateQid(requestParams, bbox, title, maxage, source);
        if (qid == null) {
            if(StringUtils.isEmpty(requestParams.getWkt())){
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to generate QID for query");
            } else {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "WKT provided has more than " + maxWktPoints + " points and failed to be simplified.");
            }
        } else {
            response.setContentType("text/plain");
            writeBytes(response, qid.getBytes());
        }
    }

    /**
     * Test presence of query params {id} in params store.
     */
    @RequestMapping(value = {"/webportal/params/{id}", "/mapping/params/{id}"}, method = RequestMethod.GET)
    public
    @ResponseBody
    Boolean storeParams(@PathVariable("id") Long id) throws Exception {
        return qidCacheDAO.get(String.valueOf(id)) != null;
    }

    /**
     * Test presence of query params {id} in params store.
     */
    @RequestMapping(value = {"/qid/{id}", "/mapping/qid/{id}"}, method = RequestMethod.GET)
    public
    @ResponseBody
    Qid showQid(@PathVariable("id") Long id) throws Exception {
        return qidCacheDAO.get(String.valueOf(id));
    }

    /**
     * Allows the details of a cached query to be viewed.
     *
     * @param id
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/params/details/{id}", "/mapping/params/details/{id}"}, method = RequestMethod.GET)
    public
    @ResponseBody
    Qid getQid(@PathVariable("id") Long id) throws Exception {
        return qidCacheDAO.get(String.valueOf(id));
    }

    /**
     * JSON web service that returns a list of species and record counts for a given location search
     *
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/species", "/mapping/species"}, method = RequestMethod.GET)
    public
    @ResponseBody
    List<TaxaCountDTO> listSpecies(SpatialSearchRequestParams requestParams) throws Exception {
        return searchDAO.findAllSpecies(requestParams);
    }

    /**
     * List of species for webportal as csv.
     *
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/species.csv", "/mapping/species.csv"}, method = RequestMethod.GET)
    public void listSpeciesCsv(
            SpatialSearchRequestParams requestParams,
            HttpServletResponse response) throws Exception {

        List<TaxaCountDTO> list = searchDAO.findAllSpecies(requestParams);

        //format as csv
        StringBuilder sb = new StringBuilder();
        sb.append("Family,Scientific name,Common name,Taxon rank,LSID,# Occurrences");
        for (TaxaCountDTO d : list) {
            String family = d.getFamily();
            String name = d.getName();
            String commonName = d.getCommonName();
            String guid = d.getGuid();
            String rank = d.getRank();

            if (family == null) {
                family = "";
            }
            if (name == null) {
                name = "";
            }
            if (commonName == null) {
                commonName = "";
            }

            if (d.getGuid() == null) {
                //when guid is empty name contains name_lsid value.
                if (d.getName() != null) {
                    //parse name
                    String[] nameLsid = d.getName().split("\\|");
                    if (nameLsid.length >= 2) {
                        name = nameLsid[0];
                        guid = nameLsid[1];
                        rank = "scientific name";

                        if (nameLsid.length >= 3) {
                            commonName = nameLsid[2];
                        }
//                        if(nameLsid.length >= 4) {
//                            kingdom = nameLsid[3];
//                        }
                    } else {
                        name = NULL_NAME;
                    }
                }
            }
            if (d.getCount() != null && guid != null) {
                sb.append("\n\"").append(family.replace("\"", "\"\"").trim()).append("\",\"").append(name.replace("\"", "\"\"").trim()).append("\",\"").append(commonName.replace("\"", "\"\"").trim()).append("\",").append(rank).append(",").append(guid).append(",").append(d.getCount());
            }
        }

        response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
        response.setHeader("ETag", wmsETag.get());

        writeBytes(response, sb.toString().getBytes("UTF-8"));
    }

    /**
     * Get legend for a query and facet field (colourMode).
     *
     * if "Accept" header is application/json return json otherwise
     *
     * @param requestParams
     * @param colourMode
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/legend", "/mapping/legend"}, method = RequestMethod.GET)
    @ResponseBody
    public List<LegendItem> legend(
            SpatialSearchRequestParams requestParams,
            @RequestParam(value = "cm", required = false, defaultValue = "") String colourMode,
            @RequestParam(value = "type", required = false, defaultValue = "application/csv") String returnType,
            HttpServletRequest request,
            HttpServletResponse response)
            throws Exception {

        String[] acceptableTypes = new String[]{"application/json", "application/csv"};

        String accepts = request.getHeader("Accept");
        //only allow a single format to be supplied in the header otherwise use the default returnType
        returnType = StringUtils.isNotEmpty(accepts) && !accepts.contains(",") ? accepts : returnType;
        if (!Arrays.asList(acceptableTypes).contains(returnType)) {
            response.sendError(response.SC_NOT_ACCEPTABLE, "Unable to produce a legend in the supplied \"Accept\" format: " + returnType);
            return null;
        }
        boolean isCsv = returnType.equals("application/csv");
        //test for cutpoints on the back of colourMode
        String[] s = colourMode.split(",");
        String[] cutpoints = null;
        if (s.length > 1) {
            cutpoints = new String[s.length - 1];
            System.arraycopy(s, 1, cutpoints, 0, cutpoints.length);
        }
        requestParams.setFormattedQuery(null);
        List<LegendItem> legend = searchDAO.getLegend(requestParams, s[0], cutpoints);
        if (cutpoints == null) {
            java.util.Collections.sort(legend);
        }
        StringBuilder sb = new StringBuilder();
        if (isCsv) {
            sb.append("name,red,green,blue,count");
        }
        int i = 0;
        //add legend entries.
        int offset = 0;
        for (i = 0; i < legend.size(); i++) {
            LegendItem li = legend.get(i);
            String name = li.getName();
            if (StringUtils.isEmpty(name)) {
                name = NULL_NAME;
            }
            int colour = DEFAULT_COLOUR;
            if (cutpoints == null) {
                colour = ColorUtil.colourList[Math.min(i, ColorUtil.colourList.length - 1)];
            } else if (cutpoints != null && i - offset < cutpoints.length) {
                if (name.equals(NULL_NAME) || name.startsWith("-")) {
                    offset++;
                    colour = DEFAULT_COLOUR;
                } else {
                    colour = ColorUtil.getRangedColour(i - offset, cutpoints.length / 2);
                }
            }
            li.setRGB(colour);
            if (isCsv) {
                sb.append("\n\"").append(name.replace("\"", "\"\"")).append("\",").append(ColorUtil.getRGB(colour)) //repeat last colour if required
                        .append(",").append(legend.get(i).getCount());
            }
        }

        response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
        response.setHeader("ETag", wmsETag.get());

        //now generate the JSON if necessary
        if (returnType.equals("application/json")) {
            return legend;
        } else {
            writeBytes(response, sb.toString().getBytes("UTF-8"));
            return null;
        }
    }

    /**
     * List data providers for a query.
     *
     * @param requestParams
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/dataProviders", "/mapping/dataProviders"}, method = RequestMethod.GET)
    @ResponseBody
    public List<DataProviderCountDTO> queryInfo(
            SpatialSearchRequestParams requestParams)
            throws Exception {
        return searchDAO.getDataProviderList(requestParams);
    }

    /**
     * Get query bounding box as csv containing:
     * min longitude, min latitude, max longitude, max latitude
     *
     * @param requestParams
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/bbox", "/mapping/bbox"}, method = RequestMethod.GET)
    public void boundingBox(
            SpatialSearchRequestParams requestParams,
            HttpServletResponse response)
            throws Exception {

        response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
        response.setHeader("ETag", wmsETag.get());

        double[] bbox = null;

        if (bbox == null) {
            bbox = searchDAO.getBBox(requestParams);
        }

        writeBytes(response, (bbox[0] + "," + bbox[1] + "," + bbox[2] + "," + bbox[3]).getBytes("UTF-8"));
    }

    /**
     * Get query bounding box as JSON array containing:
     * min longitude, min latitude, max longitude, max latitude
     *
     * @param requestParams
     * @param response
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/bounds", "/mapping/bounds"}, method = RequestMethod.GET)
    public
    @ResponseBody
    double[] jsonBoundingBox(
            SpatialSearchRequestParams requestParams,
            HttpServletResponse response)
            throws Exception {

        response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
        response.setHeader("ETag", wmsETag.get());

        double[] bbox = null;

        String q = requestParams.getQ();
        //when requestParams only contain a qid, get the bbox from the qidCache
        if (q.startsWith("qid:") && StringUtils.isEmpty(requestParams.getWkt()) &&
                (requestParams.getFq().length == 0 ||
                        (requestParams.getFq().length == 1 && StringUtils.isEmpty(requestParams.getFq()[0])))) {
            try {
                bbox = qidCacheDAO.get(q.substring(4)).getBbox();
            } catch (Exception e) {
            }
        }

        if (bbox == null) {
            bbox = searchDAO.getBBox(requestParams);
        }

        return bbox;
    }

    /**
     * Get occurrences by query as JSON.
     *
     * @param requestParams
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/occurrences*", "/mapping/occurrences*"}, method = RequestMethod.GET)
    @ResponseBody
    public SearchResultDTO occurrences(
            SpatialSearchRequestParams requestParams,
            Model model,
            HttpServletResponse response) throws Exception {

        if (StringUtils.isEmpty(requestParams.getQ())) {
            return new SearchResultDTO();
        }

        // TODO: Stream result instead of loading it all into biocache-service
        SearchResultDTO searchResult = searchDAO.findByFulltextSpatialQuery(requestParams, null);
        model.addAttribute("searchResult", searchResult);

        if (logger.isDebugEnabled()) {
            logger.debug("Returning results set with: " + searchResult.getTotalRecords());
        }

        return searchResult;
    }

    /**
     * Get occurrences by query as gzipped csv.
     *
     * @param requestParams
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/occurrences.gz", "/mapping/occurrences.gz"}, method = RequestMethod.GET)
    public void occurrenceGz(
            SpatialSearchRequestParams requestParams,
            HttpServletResponse response)
            throws Exception {

        response.setContentType("text/plain");
        response.setCharacterEncoding("gzip");

        try {
            ServletOutputStream outStream = response.getOutputStream();
            java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(outStream);

            writeOccurrencesCsvToStream(requestParams, gzip);

            gzip.flush();
            gzip.close();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void writeOccurrencesCsvToStream(SpatialSearchRequestParams requestParams, OutputStream stream) throws Exception {
        ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).streamingQuery(requestParams, null, new StreamAsCSV(stream, requestParams), null);
    }

    private void writeBytes(HttpServletResponse response, byte[] bytes) throws IOException {
        try {
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");
            ServletOutputStream outStream = response.getOutputStream();
            outStream.write(bytes);
            outStream.flush();
            outStream.close();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Map a zoom level to a coordinate accuracy level
     *
     * @return
     */
    protected PointType getPointTypeForDegreesPerPixel(double resolution) {
        PointType pointType = null;
        // Map zoom levels to lat/long accuracy levels
        if (resolution >= 1) {
            pointType = PointType.POINT_1;
        } else if (resolution >= 0.1) {
            pointType = PointType.POINT_01;
        } else if (resolution >= 0.01) {
            pointType = PointType.POINT_001;
        } else if (resolution >= 0.001) {
            pointType = PointType.POINT_0001;
        } else if (resolution >= 0.0001) {
            pointType = PointType.POINT_00001;
        } else {
            pointType = PointType.POINT_RAW;
        }
        return pointType;
    }

    void displayBlankImage(HttpServletResponse response) {
        try {
            ServletOutputStream outStream = response.getOutputStream();
            outStream.write(blankImageBytes);
            outStream.flush();
            outStream.close();
        } catch (Exception e) {
            logger.error("Unable to write image", e);
        }
    }

    /**
     *
     * @param transformTo4326 TransformOp to convert from the target SRS to the coordinates in SOLR (EPSG:4326)
     * @param bboxString getMap bbox parameter with the tile extents in the target SRS as min x, min y, max x, max y
     * @param width getMap width value in pixels
     * @param height getMap height value in pixels
     * @param size dot radius in pixels used to calculate a larger bounding box for the request to SOLR for coordinates
     * @param uncertainty boolean to trigger a larger bounding box for the request to SOLR for coordinates (??)
     * @param mbbox bounding box in metres (spherical mercator). Includes pixel correction buffer.
     * @param bbox bounding box for the SOLR request. This is the bboxString transformed to EPSG:4326 with buffer of
     *            dot size + max uncertainty radius + pixel correction
     * @param pbbox bounding box in the target SRS corresponding to the output pixel positions. Includes pixel correction buffer.
     * @param tilebbox raw coordinates from the getMap bbox parameter
     * @return degrees per pixel to determine which SOLR coordinate field to facet upon
     */
    private double getBBoxesSRS(CoordinateOperation transformTo4326, String bboxString, int width, int height, int size, boolean uncertainty, double[] mbbox, double[] bbox, double[] pbbox, double[] tilebbox) throws TransformException {
        String[] splitBBox = bboxString.split(",");
        for (int i = 0; i < 4; i++) {
            try {
                tilebbox[i] = Double.parseDouble(splitBBox[i]);
                mbbox[i] = tilebbox[i];
            } catch (Exception e) {
                logger.error("Problem parsing BBOX: '" + bboxString + "' at position " + i, e);
                tilebbox[i] = 0.0d;
                mbbox[i] = 0.0d;
            }
        }

        // pixel correction buffer: adjust bbox extents with half pixel width/height
        double pixelWidthInTargetSRS = (mbbox[2] - mbbox[0]) / (double) width;
        double pixelHeightInTargetSRS = (mbbox[3] - mbbox[1]) / (double) height;
        mbbox[0] += pixelWidthInTargetSRS / 2.0;
        mbbox[2] -= pixelWidthInTargetSRS / 2.0;
        mbbox[1] += pixelHeightInTargetSRS / 2.0;
        mbbox[3] -= pixelHeightInTargetSRS / 2.0;

        // when an SRS is not aligned with EPSG:4326 the dot size may be too small.
        double srsOffset = 10;

        //offset for points bounding box by dot size
        double xoffset = pixelWidthInTargetSRS * (size + 1) * srsOffset;
        double yoffset = pixelHeightInTargetSRS * (size + 1) * srsOffset;

        pbbox[0] = mbbox[0];
        pbbox[1] = mbbox[1];
        pbbox[2] = mbbox[2];
        pbbox[3] = mbbox[3];

        GeneralDirectPosition directPositionSW = new GeneralDirectPosition(mbbox[0] - xoffset, mbbox[1] - yoffset);
        GeneralDirectPosition directPositionNE = new GeneralDirectPosition(mbbox[2] + xoffset, mbbox[3] + yoffset);
        GeneralDirectPosition directPositionSE = new GeneralDirectPosition(mbbox[2] - xoffset, mbbox[1] - yoffset);
        GeneralDirectPosition directPositionNW = new GeneralDirectPosition(mbbox[0] + xoffset, mbbox[3] + yoffset);
        DirectPosition sw4326 = transformTo4326.getMathTransform().transform(directPositionSW, null);
        DirectPosition ne4326 = transformTo4326.getMathTransform().transform(directPositionNE, null);
        DirectPosition se4326 = transformTo4326.getMathTransform().transform(directPositionSE, null);
        DirectPosition nw4326 = transformTo4326.getMathTransform().transform(directPositionNW, null);

        bbox[0] = Math.min(Math.min(Math.min(sw4326.getOrdinate(0), ne4326.getOrdinate(0)), se4326.getOrdinate(0)), nw4326.getOrdinate(0));
        bbox[1] = Math.min(Math.min(Math.min(sw4326.getOrdinate(1), ne4326.getOrdinate(1)), se4326.getOrdinate(1)), nw4326.getOrdinate(1));
        bbox[2] = Math.max(Math.max(Math.max(sw4326.getOrdinate(0), ne4326.getOrdinate(0)), se4326.getOrdinate(0)), nw4326.getOrdinate(0));
        bbox[3] = Math.max(Math.max(Math.max(sw4326.getOrdinate(1), ne4326.getOrdinate(1)), se4326.getOrdinate(1)), nw4326.getOrdinate(1));

        double degreesPerPixel = Math.min((bbox[2] - bbox[0]) / (double) width,
                (bbox[3] - bbox[1]) / (double) height);

        return degreesPerPixel;
    }

    // add this to the GetCapabilities...
    @RequestMapping(value = {"/ogc/getMetadata"}, method = RequestMethod.GET)
    public String getMetadata(
            GetMetadata getMetadata,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws Exception {
        String taxonName = "";
        String rank = "";
        String q = "";
        if (StringUtils.trimToNull(getMetadata.getLayer()) != null) {
            String[] parts = getMetadata.getLayer().split(":");
            taxonName = parts[parts.length - 1];
            if (parts.length > 1) {
                rank = parts[0];
            }
            q = getMetadata.getLayer();
        } else if (StringUtils.trimToNull(getMetadata.getQuery()) != null) {
            String[] parts = getMetadata.getQuery().split(":");
            taxonName = parts[parts.length - 1];
            if (parts.length > 1) {
                rank = parts[0];
            }
            q = getMetadata.getQuery();
        } else {
            response.sendError(400);
        }

        ObjectMapper om = new ObjectMapper();
        String guid = null;
        JsonNode guidLookupNode = om.readTree(new URL(bieWebService + "/guid/" + URLEncoder.encode(taxonName, "UTF-8")));
        //NC: Fixed the ArraryOutOfBoundsException when the lookup fails to yield a result
        if (guidLookupNode.isArray() && guidLookupNode.size() > 0) {
            JsonNode idNode = guidLookupNode.get(0).get("acceptedIdentifier");//NC: changed to used the acceptedIdentifier because this will always hold the guid for the accepted taxon concept whether or not a synonym name is provided
            guid = idNode != null ? idNode.asText() : null;
        }
        String newQuery = "raw_name:" + taxonName;
        if (guid != null) {

            model.addAttribute("guid", guid);
            model.addAttribute("speciesPageUrl", bieUiUrl + "/species/" + guid);
            JsonNode node = om.readTree(new URL(bieWebService + "/species/info/" + guid + ".json"));
            JsonNode tc = node.get("taxonConcept");
            JsonNode imageNode = tc.get("smallImageUrl");
            String imageUrl = imageNode != null ? imageNode.asText() : null;
            if (imageUrl != null) {
                model.addAttribute("imageUrl", imageUrl);
                JsonNode imageMetadataNode = node.get("taxonConcept").get("imageMetadataUrl");
                String imageMetadataUrl = imageMetadataNode != null ? imageMetadataNode.asText() : null;

                //image metadata
                JsonNode imageMetadata = om.readTree(new URL(imageMetadataUrl));
                if (imageMetadata != null) {
                    if (imageMetadata.get("http://purl.org/dc/elements/1.1/creator") != null)
                        model.addAttribute("imageCreator", imageMetadata.get("http://purl.org/dc/elements/1.1/creator").asText());
                    if (imageMetadata.get("http://purl.org/dc/elements/1.1/license") != null)
                        model.addAttribute("imageLicence", imageMetadata.get("http://purl.org/dc/elements/1.1/license").asText());
                    if (imageMetadata.get("http://purl.org/dc/elements/1.1/source") != null)
                        model.addAttribute("imageSource", imageMetadata.get("http://purl.org/dc/elements/1.1/source").asText());
                }
            }

            JsonNode leftNode = tc.get("left");
            JsonNode rightNode = tc.get("right");
            newQuery = leftNode != null && rightNode != null ? "lft:[" + leftNode.asText() + " TO " + rightNode.asText() + "]" : "taxon_concept_lsid:" + guid;
            if (logger.isDebugEnabled()) {
                logger.debug("The new query : " + newQuery);
            }

            //common name
            JsonNode commonNameNode = tc.get("commonNameSingle");
            if (commonNameNode != null) {
                model.addAttribute("commonName", commonNameNode.asText());
                if (logger.isDebugEnabled()) {
                    logger.debug("retrieved name: " + commonNameNode.asText());
                }
            }

            //name
            JsonNode nameNode = tc.get("nameComplete");
            if (nameNode != null) {
                model.addAttribute("name", nameNode.asText());
                if (logger.isDebugEnabled()) {
                    logger.debug("retrieved name: " + nameNode.asText());
                }
            }

            //authorship
            JsonNode authorshipNode = node.get("taxonConcept").get("author");
            if (authorshipNode != null) model.addAttribute("authorship", authorshipNode.asText());

            //taxonomic information
            JsonNode node2 = om.readTree(new URL(bieWebService + "/species/" + guid + ".json"));
            JsonNode classificationNode = node2.get("classification");
            model.addAttribute("kingdom", StringUtils.capitalize(classificationNode.get("kingdom").asText().toLowerCase()));
            model.addAttribute("phylum", StringUtils.capitalize(classificationNode.get("phylum").asText().toLowerCase()));
            model.addAttribute("clazz", StringUtils.capitalize(classificationNode.get("clazz").asText().toLowerCase()));
            model.addAttribute("order", StringUtils.capitalize(classificationNode.get("order").asText().toLowerCase()));
            model.addAttribute("family", StringUtils.capitalize(classificationNode.get("family").asText().toLowerCase()));
            model.addAttribute("genus", classificationNode.get("genus").asText());

            JsonNode taxonNameNode = node2.get("taxonName");
            if (taxonNameNode != null && taxonNameNode.get("specificEpithet") != null) {
                model.addAttribute("specificEpithet", taxonNameNode.get("specificEpithet").asText());
            }
        }

        SpatialSearchRequestParams searchParams = new SpatialSearchRequestParams();
        searchParams.setQ(newQuery);
        searchParams.setFacets(new String[]{"data_resource"});
        searchParams.setPageSize(0);
        List<FacetResultDTO> facets = searchDAO.getFacetCounts(searchParams);
        model.addAttribute("query", newQuery); //need a facet on data providers
        model.addAttribute("dataProviders", facets.get(0).getFieldResult()); //need a facet on data providers
        return "metadata/mcp";
    }

    @RequestMapping(value = {"/ogc/getFeatureInfo"}, method = RequestMethod.GET)
    public String getFeatureInfo(
            GetFeatureInfo getFeatureInfo,
            Model model) throws Exception {

        if (logger.isDebugEnabled()) {
            logger.debug("WMS - GetFeatureInfo requested for: " + getFeatureInfo.getQuery_layers());
        }

        WmsEnv vars = new WmsEnv(getFeatureInfo.getEnv(), getFeatureInfo.getStyles());
        double[] mbbox = new double[4];
        double[] bbox = new double[4];
        double[] pbbox = new double[4];
        double[] tilebbox = new double[4];
        int size = vars.size + (vars.highlight != null ? HIGHLIGHT_RADIUS * 2 + (int) (vars.size * 0.2) : 0) + 5;  //bounding box buffer

        CRSAuthorityFactory factory = CRS.getAuthorityFactory(true);
        CoordinateReferenceSystem sourceCRS = factory.createCoordinateReferenceSystem(getFeatureInfo.getSrs());
        CoordinateReferenceSystem targetCRS = factory.createCoordinateReferenceSystem("EPSG:4326");
        CoordinateOperation transformTo4326 = new DefaultCoordinateOperationFactory().createOperation(sourceCRS, targetCRS);

        double resolution;

        // support for any srs
        resolution = getBBoxesSRS(transformTo4326, getFeatureInfo.getBbox(), getFeatureInfo.getWidth(),
                getFeatureInfo.getHeight(), size, vars.uncertainty, mbbox, bbox, pbbox, tilebbox);

        //resolution should be a value < 1
        PointType pointType = getPointTypeForDegreesPerPixel(resolution);

        double longitude = bbox[0] + (((bbox[2] - bbox[0]) / getFeatureInfo.getWidth().doubleValue()) * getFeatureInfo.getX());
        double latitude = bbox[3] - (((bbox[3] - bbox[1]) / getFeatureInfo.getHeight().doubleValue()) * getFeatureInfo.getY());

        //get the pixel size of the circles
        double minLng = pointType.roundDownToPointType(longitude - (pointType.getValue() * 2 * (size + 3)));
        double maxLng = pointType.roundUpToPointType(latitude + (pointType.getValue() * 2 * (size + 3)));
        double minLat = pointType.roundDownToPointType(longitude - (pointType.getValue() * 2 * (size + 3)));
        double maxLat = pointType.roundUpToPointType(latitude + (pointType.getValue() * 2 * (size + 3)));

        //do the SOLR query
        SpatialSearchRequestParams requestParams = new SpatialSearchRequestParams();
        String q = WMSUtils.convertLayersParamToQ(getFeatureInfo.getQuery_layers());
        requestParams.setQ(WMSUtils.convertLayersParamToQ(getFeatureInfo.getQuery_layers()));  //need to derive this from the layer name
        if (logger.isDebugEnabled()) {
            logger.debug("WMS GetFeatureInfo for " + getFeatureInfo.getQuery_layers() + ", longitude:[" + minLng + " TO " + maxLng + "],  latitude:[" + minLat + " TO " + maxLat + "]");
        }

        String[] fqs = new String[]{"longitude:[" + minLng + " TO " + maxLng + "]", "latitude:[" + minLat + " TO " + maxLat + "]"};
        requestParams.setFq(fqs);
        requestParams.setFacet(false);

        // TODO: Stream result instead of loading it all into biocache-service
        SolrDocumentList sdl = searchDAO.findByFulltext(requestParams);

        //send back the results.
        if (sdl != null && sdl.size() > 0) {
            SolrDocument doc = sdl.get(0);
            model.addAttribute("record", doc.getFieldValueMap());
            model.addAttribute("totalRecords", sdl.getNumFound());
        }

        model.addAttribute("uriUrl", baseUiUrl + "/occurrences/search?q=" +
                URLEncoder.encode(q == null ? "" : q, "UTF-8")
                + "&fq=" + URLEncoder.encode(fqs[0], "UTF-8")
                + "&fq=" + URLEncoder.encode(fqs[1], "UTF-8")
        );

        model.addAttribute("pointType", pointType.name());
        model.addAttribute("minLng", minLng);
        model.addAttribute("maxLng", maxLng);
        model.addAttribute("minLat", minLat);
        model.addAttribute("maxLat", maxLat);
        model.addAttribute("latitudeClicked", latitude);
        model.addAttribute("longitudeClicked", longitude);

        return "metadata/getFeatureInfo";
    }

    @RequestMapping(value = {"/ogc/legendGraphic"}, method = RequestMethod.GET)
    public void getLegendGraphic(
            GetLegendGraphic legendGraphic,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        try {
            if (StringUtils.trimToNull(legendGraphic.getEnv()) == null && StringUtils.trimToNull(legendGraphic.getStyle()) == null) {
                legendGraphic.setStyle("8b0000;opacity=1;size=5");
            }

            WmsEnv wmsEnv = new WmsEnv(legendGraphic.getEnv(), legendGraphic.getStyle());
            BufferedImage img = new BufferedImage(legendGraphic.getWidth(), legendGraphic.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = (Graphics2D) img.getGraphics();
            int size = legendGraphic.getWidth() > legendGraphic.getHeight() ? legendGraphic.getHeight() : legendGraphic.getWidth();
            Paint fill = new Color(wmsEnv.colour | wmsEnv.alpha << 24);
            g.setPaint(fill);
            g.fillOval(0, 0, size, size);
            if (logger.isDebugEnabled()) {
                logger.debug("WMS - GetLegendGraphic requested : " + request.getQueryString());
            }
            try (OutputStream out = response.getOutputStream();) {
                response.setContentType("image/png");
                response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
                response.setHeader("ETag", wmsETag.get());
                ImageIO.write(img, "png", out);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Returns a get capabilities response by default.
     *
     * @param requestParams
     * @param getOgc
     * @param request
     * @param response
     * @param model
     * @throws Exception
     */
    @RequestMapping(value = {"/ogc/ows", "/ogc/capabilities"}, method = RequestMethod.GET)
    public void getCapabilities(
            SpatialSearchRequestParams requestParams,
            GetOgc getOgc,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model)
            throws Exception {

        if ("GetMap".equalsIgnoreCase(getOgc.getRequest())) {
            generateWmsTile(requestParams, getOgc, request, response);
            return;
        }

        if ("GetLegendGraphic".equalsIgnoreCase(getOgc.getRequest())) {
            GetLegendGraphic legendGraphic = new GetLegendGraphic();
            legendGraphic.setEnv(getOgc.getEnv());
            if (StringUtils.isEmpty((getOgc.getStyle()))) legendGraphic.setStyle(getOgc.getStyles());
            else legendGraphic.setStyle(getOgc.getStyle());
            getLegendGraphic(getOgc, request, response);
            return;
        }

        if ("GetFeatureInfo".equalsIgnoreCase(getOgc.getRequest())) {
            getFeatureInfo(getOgc, model);
            return;
        }

        //add the get capabilities request

        response.setContentType("text/xml");
        response.setHeader("Content-Description", "File Transfer");
        response.setHeader("Content-Disposition", "attachment; filename=GetCapabilities.xml");
        response.setHeader("Content-Transfer-Encoding", "binary");
        try {
            //webservicesRoot
            String biocacheServerUrl = request.getSession().getServletContext().getInitParameter("webservicesRoot");
            PrintWriter writer = response.getWriter();

            String supportedCodes = "";
            for (String code : CRS.getSupportedCodes("EPSG")) {
                supportedCodes += "      <SRS>EPSG:" + code + "</SRS>\n";
            }

            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<!DOCTYPE WMT_MS_Capabilities SYSTEM \"http://spatial.ala.org.au/geoserver/schemas/wms/1.1.1/WMS_MS_Capabilities.dtd\">\n" +
                    "<WMT_MS_Capabilities version=\"1.1.1\" updateSequence=\"28862\">\n" +
                    "  <Service>\n" +
                    "    <Name>OGC:WMS</Name>\n" +
                    "    <Title>" + organizationName + "(WMS) - Species occurrences</Title>\n" +
                    "    <Abstract>WMS services for species occurrences.</Abstract>\n" +
                    "    <KeywordList>\n" +
                    "      <Keyword>WMS</Keyword>\n" +
                    "      <Keyword>Species occurrence data</Keyword>\n" +
                    "      <Keyword>ALA</Keyword>\n" +
                    "      <Keyword>CRIS</Keyword>\n" +
                    "    </KeywordList>\n" +
                    "    <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + biocacheServerUrl + "/ogc/wms\"/>\n" +
                    "    <ContactInformation>\n" +
                    "      <ContactPersonPrimary>\n" +
                    "        <ContactPerson>ALA Support</ContactPerson>\n" +
                    "        <ContactOrganization>" + organizationName + "</ContactOrganization>\n" +
                    "      </ContactPersonPrimary>\n" +
                    "      <ContactPosition>Support Manager</ContactPosition>\n" +
                    "      <ContactAddress>\n" +
                    "        <AddressType></AddressType>\n" +
                    "        <Address/>\n" +
                    "        <City>" + orgCity + "</City>\n" +
                    "        <StateOrProvince>" + orgStateProvince + "</StateOrProvince>\n" +
                    "        <PostCode>" + orgPostcode + "</PostCode>\n" +
                    "        <Country>" + orgCountry + "</Country>\n" +
                    "      </ContactAddress>\n" +
                    "      <ContactVoiceTelephone>" + orgPhone + "</ContactVoiceTelephone>\n" +
                    "      <ContactFacsimileTelephone>" + orgFax + "</ContactFacsimileTelephone>\n" +
                    "      <ContactElectronicMailAddress>" + orgEmail + "</ContactElectronicMailAddress>\n" +
                    "    </ContactInformation>\n" +
                    "    <Fees>NONE</Fees>\n" +
                    "    <AccessConstraints>NONE</AccessConstraints>\n" +
                    "  </Service>\n" +
                    "  <Capability>\n" +
                    "    <Request>\n" +
                    "      <GetCapabilities>\n" +
                    "        <Format>application/vnd.ogc.wms_xml</Format>\n" +
                    "        <DCPType>\n" +
                    "          <HTTP>\n" +
                    "            <Get>\n" +
                    "              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + baseWsUrl + "/ogc/capabilities?SERVICE=WMS&amp;\"/>\n" +
                    "            </Get>\n" +
                    "            <Post>\n" +
                    "              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + baseWsUrl + "/ogc/capabilities?SERVICE=WMS&amp;\"/>\n" +
                    "            </Post>\n" +
                    "          </HTTP>\n" +
                    "        </DCPType>\n" +
                    "      </GetCapabilities>\n" +
                    "      <GetMap>\n" +
                    "        <Format>image/png</Format>\n" +
                    "        <DCPType>\n" +
                    "          <HTTP>\n" +
                    "            <Get>\n" +
                    "              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + baseWsUrl + "/ogc/wms/reflect?SERVICE=WMS&amp;OUTLINE=TRUE&amp;\"/>\n" +
                    "            </Get>\n" +
                    "          </HTTP>\n" +
                    "        </DCPType>\n" +
                    "      </GetMap>\n" +
                    "      <GetFeatureInfo>\n" +
                    "        <Format>text/html</Format>\n" +
                    "        <DCPType>\n" +
                    "          <HTTP>\n" +
                    "            <Get>\n" +
                    "              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + baseWsUrl + "/ogc/getFeatureInfo\"/>\n" +
                    "            </Get>\n" +
                    "            <Post>\n" +
                    "              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + baseWsUrl + "/ogc/getFeatureInfo\"/>\n" +
                    "            </Post>\n" +
                    "          </HTTP>\n" +
                    "        </DCPType>\n" +
                    "      </GetFeatureInfo>\n" +
                    "      <GetLegendGraphic>\n" +
                    "        <Format>image/png</Format>\n" +
                    "        <Format>image/jpeg</Format>\n" +
                    "        <Format>image/gif</Format>\n" +
                    "        <DCPType>\n" +
                    "          <HTTP>\n" +
                    "            <Get>\n" +
                    "              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\" xlink:href=\"" + baseWsUrl + "/ogc/legendGraphic\"/>\n" +
                    "            </Get>\n" +
                    "          </HTTP>\n" +
                    "        </DCPType>\n" +
                    "      </GetLegendGraphic>\n" +
                    "    </Request>\n" +
                    "    <Exception>\n" +
                    "      <Format>application/vnd.ogc.se_xml</Format>\n" +
                    "      <Format>application/vnd.ogc.se_inimage</Format>\n" +
                    "    </Exception>\n" +
                    "    <Layer>\n" +
                    "      <Title>" + organizationName + " - Species occurrence layers</Title>\n" +
                    "      <Abstract>Custom WMS services for " + organizationName + " species occurrences</Abstract>\n" +
                    supportedCodes +
                    "     <LatLonBoundingBox minx=\"-179.9\" miny=\"-89.9\" maxx=\"179.9\" maxy=\"89.9\"/>\n"
            );

            writer.write(generateStylesForPoints());

            String[] filterQueries = requestParams.getFq();
            if (filterQueries == null) filterQueries = new String[]{};

            if (getOgc.isSpatiallyValidOnly()) {
                filterQueries = org.apache.commons.lang3.ArrayUtils.add(filterQueries, "geospatial_kosher:true");
            }

            if (getOgc.isMarineOnly()) {
                filterQueries = org.apache.commons.lang3.ArrayUtils.add(filterQueries, "species_habitats:Marine OR species_habitats:\"Marine and Non-marine\"");
            }

            if (getOgc.isTerrestrialOnly()) {
                filterQueries = org.apache.commons.lang3.ArrayUtils.add(filterQueries, "species_habitats:\"Non-marine\" OR species_habitats:Limnetic");
            }

            if (getOgc.isLimitToFocus()) {
                filterQueries = org.apache.commons.lang3.ArrayUtils.add(filterQueries, limitToFocusValue);
            }

            String query = requestParams.getQ();
            query = searchUtils.convertRankAndName(query);
            if (logger.isDebugEnabled()) {
                logger.debug("GetCapabilities query in use: " + query);
            }

            if (getOgc.isUseSpeciesGroups()) {
                taxonDAO.extractBySpeciesGroups(baseWsUrl + "/ogc/getMetadata", query, filterQueries, writer);
            } else {
                taxonDAO.extractHierarchy(baseWsUrl + "/ogc/getMetadata", query, filterQueries, writer);
            }

            writer.write("</Layer></Capability></WMT_MS_Capabilities>\n");

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public String generateStylesForPoints() {
        //need a better listings of colours
        String[] sizes = new String[]{"5", "10", "2"};
        String[] sizesNames = new String[]{"medium", "large", "small"};
        String[] opacities = new String[]{"0.5", "1", "0.2"};
        String[] opacitiesNames = new String[]{"medium", "opaque", "transparency"};
        StringBuffer sb = new StringBuffer();
        int colorIdx = 0;
        int sizeIdx = 0;
        int opIdx = 0;
        for (String color : ColorUtil.colorsNames) {
            for (String size : sizes) {
                for (String opacity : opacities) {
                    sb.append(
                            "<Style>\n" +
                                    "<Name>" + ColorUtil.colorsCodes[colorIdx] + ";opacity=" + opacity + ";size=" + size + "</Name> \n" +
                                    "<Title>" + color + ";opacity=" + opacitiesNames[opIdx] + ";size=" + sizesNames[sizeIdx] + "</Title> \n" +
                                    "</Style>\n"
                    );
                    opIdx++;
                }
                opIdx = 0;
                sizeIdx++;
            }
            sizeIdx = 0;
            colorIdx++;
        }
        return sb.toString();
    }

    @RequestMapping(value = {"/webportal/wms/clearCache", "/ogc/wms/clearCache", "/mapping/wms/clearCache"}, method = RequestMethod.GET)
    public void clearWMSCache(HttpServletResponse response,
                              @RequestParam(value = "apiKey") String apiKey) throws Exception {
        if (isValidKey(apiKey)) {
            wmsCache.empty();
            response.setStatus(200);
            regenerateWMSETag();
        } else {
            response.setStatus(401);
        }
    }

    /**
     * Regenerate the ETag after clearing the WMS cache so that cached responses are identified as out of date
     */
    private void regenerateWMSETag() {
        wmsETag.set(UUID.randomUUID().toString());
    }

    /**
     * WMS service for webportal.
     *
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = {"/webportal/wms/reflect", "/ogc/wms/reflect", "/mapping/wms/reflect"}, method = RequestMethod.GET)
    public ModelAndView generateWmsTile(
            SpatialSearchRequestParams requestParams,
            GetMap getMap,
            HttpServletRequest request,
            HttpServletResponse response)
            throws Exception {

        //for OS Grids, hand over to WMS OS controller
        if (getMap.getEnv() != null && getMap.getEnv().contains("osgrid")) {
            wmsosGridController.generateWmsTile(requestParams, getMap, request, response);
            return null;
        }

        //correct cache value
        if ("default".equals(getMap.getCache())) getMap.setCache(wmsCacheEnabled ? "on" : "off");

        //Some WMS clients are ignoring sections of the GetCapabilities....
        if ("GetLegendGraphic".equalsIgnoreCase(getMap.getRequest())) {
            GetLegendGraphic legendGraphic = new GetLegendGraphic();
            legendGraphic.setEnv(getMap.getEnv());
            legendGraphic.setStyle(getMap.getStyles());
            getLegendGraphic(legendGraphic, request, response);
            return null;
        }

        if (StringUtils.isBlank(getMap.getBbox())) {
            return sendWmsError(response, 400, "MissingOrInvalidParameter",
                    "Missing valid BBOX parameter");
        }

        Set<Integer> hq = new HashSet<Integer>();
        if (getMap.getHq() != null && getMap.getHq().length > 0) {
            for (String h : getMap.getHq()) {
                hq.add(Integer.parseInt(h));
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("WMS tile: " + request.getQueryString());
        }

        response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
        response.setHeader("ETag", wmsETag.get());
        response.setContentType("image/png"); //only png images generated

        WmsEnv vars = new WmsEnv(getMap.getEnv(), getMap.getStyles());
        double[] mbbox = new double[4];
        double[] bbox = new double[4];
        double[] pbbox = new double[4];
        double[] tilebbox = new double[4];

        //bbox adjustment for WMSCache is better with a stepped size
        int steppedSize = (int) (Math.ceil(vars.size / 20.0) * 20);
        int size = steppedSize + (vars.highlight != null ? HIGHLIGHT_RADIUS * 2 + (int) (steppedSize * 0.2) : 0) + 5;  //bounding box buffer

        CRSAuthorityFactory factory = CRS.getAuthorityFactory(true);
        CoordinateReferenceSystem sourceCRS = factory.createCoordinateReferenceSystem(getMap.getSrs());
        CoordinateReferenceSystem targetCRS = factory.createCoordinateReferenceSystem("EPSG:4326");
        CoordinateOperation transformTo4326 = new DefaultCoordinateOperationFactory().createOperation(sourceCRS, targetCRS);
        CoordinateOperation transformFrom4326 = new DefaultCoordinateOperationFactory().createOperation(targetCRS, sourceCRS);

        double resolution;

        // support for any srs
        resolution = getBBoxesSRS(transformTo4326, getMap.getBbox(), getMap.getWidth(), getMap.getHeight(), size,
                vars.uncertainty, mbbox, bbox, pbbox, tilebbox);

        PointType pointType = getPointTypeForDegreesPerPixel(resolution);
        if (logger.isDebugEnabled()) {
            logger.debug("Rendering: " + pointType.name());
        }

        String[] boundingBoxFqs = new String[2];
        boundingBoxFqs[0] = String.format("longitude:[%f TO %f]", bbox[0], bbox[2]);
        boundingBoxFqs[1] = String.format("latitude:[%f TO %f]", bbox[1], bbox[3]);

        int pointWidth = vars.size * 2;
        double width_mult = (getMap.getWidth() / (pbbox[2] - pbbox[0]));
        double height_mult = (getMap.getHeight() / (pbbox[1] - pbbox[3]));


        //CQL Filter takes precedence of the layer
        String q = "";
        if (StringUtils.trimToNull(getMap.getCqlFilter()) != null) {
            q = WMSUtils.getQ(getMap.getCqlFilter());
        } else if (StringUtils.trimToNull(getMap.getLayers()) != null && !"ALA:Occurrences".equalsIgnoreCase(getMap.getLayers())) {
            q = WMSUtils.convertLayersParamToQ(getMap.getLayers());
        }

        //build request
        if (q.length() > 0) {
            requestParams.setQ(q);
        } else {
            q = requestParams.getQ();
        }

        //bounding box test (requestParams must be 'qid:' + number only)
        if (q.startsWith("qid:") && StringUtils.isEmpty(requestParams.getWkt()) &&
                (requestParams.getFq().length == 0 ||
                        (requestParams.getFq().length == 1 && StringUtils.isEmpty(requestParams.getFq()[0])))) {
            double[] queryBBox = qidCacheDAO.get(q.substring(4)).getBbox();
            if (queryBBox != null && (queryBBox[0] > bbox[2] || queryBBox[2] < bbox[0]
                    || queryBBox[1] > bbox[3] || queryBBox[3] < bbox[1])) {
                displayBlankImage(response);
                return null;
            }
        }

        String[] originalFqs = qidCacheDAO.getFq(requestParams);

        //get from cache, or make it
        boolean canCache = wmsCache.isEnabled() && "on".equalsIgnoreCase(getMap.getCache());
        WMSTile wco = getWMSCacheObject(requestParams, vars, pointType, bbox, originalFqs, boundingBoxFqs, canCache);

        //correction for gridDivisionCount
        boolean isGrid = vars.colourMode.equals("grid");
        if (isGrid) {
            if (getMap.getGridDetail() > Math.min(getMap.getWidth(), getMap.getHeight())) {
                getMap.setGridDetail(Math.min(getMap.getWidth(), getMap.getHeight()));
            }
            if (getMap.getGridDetail() < 0) {
                getMap.setGridDetail(1);
            }

            //gridDivisionCount correction
            while (getMap.getWidth() % getMap.getGridDetail() > 0 || getMap.getHeight() % getMap.getGridDetail() > 0) {
                getMap.setGridDetail(getMap.getGridDetail() - 1);
            }
        }

        ImgObj imgObj = wco.getPoints() == null ? null :
                wmsCached(wco, requestParams, vars, pointType, pbbox, bbox, mbbox, getMap.getWidth(),
                        getMap.getHeight(), width_mult, height_mult, pointWidth, originalFqs, hq, boundingBoxFqs,
                        getMap.isOutline(), getMap.getOutlineColour(), response, tilebbox, getMap.getGridDetail(),
                        transformFrom4326);

        if (imgObj != null && imgObj.g != null) {
            imgObj.g.dispose();
            try (ServletOutputStream outStream = response.getOutputStream();){
                ImageIO.write(imgObj.img, "png", outStream);
                outStream.flush();
            } catch (Exception e) {
                logger.debug("Unable to write image", e);
            }
        } else {
            displayBlankImage(response);
        }
        return null;
    }

    private ModelAndView sendWmsError(HttpServletResponse response, int status, String errorType, String errorDescription) {
        response.setStatus(status);
        Map<String,String> model = new HashMap<String,String>();
        model.put("errorType", errorType);
        model.put("errorDescription", errorDescription);
        return new ModelAndView("wms/error", model);
    }

    private void transformBBox(CoordinateOperation op, String bbox, double[] source, double[] target) throws TransformException {
        String[] bb = bbox.split(",");

        source[0] = Double.parseDouble(bb[0]);
        source[1] = Double.parseDouble(bb[1]);
        source[2] = Double.parseDouble(bb[2]);
        source[3] = Double.parseDouble(bb[3]);

        GeneralDirectPosition sw = new GeneralDirectPosition(source[0], source[1]);
        GeneralDirectPosition ne = new GeneralDirectPosition(source[2], source[3]);
        GeneralDirectPosition se = new GeneralDirectPosition(source[2], source[1]);
        GeneralDirectPosition nw = new GeneralDirectPosition(source[0], source[3]);
        DirectPosition targetSW = op.getMathTransform().transform(sw, null);
        DirectPosition targetNE = op.getMathTransform().transform(ne, null);
        DirectPosition targetSE = op.getMathTransform().transform(se, null);
        DirectPosition targetNW = op.getMathTransform().transform(nw, null);

        target[0] = Math.min(Math.min(Math.min(targetSW.getOrdinate(0), targetNE.getOrdinate(0)), targetSE.getOrdinate(0)), targetNW.getOrdinate(0));
        target[1] = Math.min(Math.min(Math.min(targetSW.getOrdinate(1), targetNE.getOrdinate(1)), targetSE.getOrdinate(1)), targetNW.getOrdinate(1));
        target[2] = Math.max(Math.max(Math.max(targetSW.getOrdinate(0), targetNE.getOrdinate(0)), targetSE.getOrdinate(0)), targetNW.getOrdinate(0));
        target[3] = Math.max(Math.max(Math.max(targetSW.getOrdinate(1), targetNE.getOrdinate(1)), targetSE.getOrdinate(1)), targetNW.getOrdinate(1));
    }

    /**
     * Method that produces the downloadable map integrated in AVH/OZCAM/Biocache.
     */
    @RequestMapping(value = {"/webportal/wms/image", "/mapping/wms/image"}, method = RequestMethod.GET)
    public void generatePublicationMap(
            SpatialSearchRequestParams requestParams,
            GetImage getImage,
            HttpServletRequest request, HttpServletResponse response) throws Exception {

        // convert extents from EPSG:4326 into target SRS
        CRSAuthorityFactory factory = CRS.getAuthorityFactory(true);
        CoordinateReferenceSystem sourceCRS = factory.createCoordinateReferenceSystem(getImage.getSrs());
        CoordinateReferenceSystem targetCRS = factory.createCoordinateReferenceSystem("EPSG:4326");
        CoordinateOperation transformTo4326 = new DefaultCoordinateOperationFactory().createOperation(sourceCRS, targetCRS);
        CoordinateOperation transformFrom4326 = new DefaultCoordinateOperationFactory().createOperation(targetCRS, sourceCRS);
        double[] bbox4326 = new double[4];     // extents in EPSG:4326
        double[] bboxSRS = new double[4];      //extents in target SRS
        if (getImage.getBbox() != null) {
            transformBBox(transformTo4326, getImage.getBbox(), bboxSRS, bbox4326);
        } else {
            transformBBox(transformFrom4326, getImage.getExtents(), bbox4326, bboxSRS);
            getImage.setBbox(bboxSRS[0] + "," + bboxSRS[1] + "," + bboxSRS[2] + "," + bboxSRS[3]);
        }

        int width = (int) ((getImage.getDpi() / 25.4) * getImage.getWidthMm());
        int height = (int) Math.round(width * ((bboxSRS[3] - bboxSRS[1]) / (bboxSRS[2] - bboxSRS[0])));

        if (height * width > MAX_IMAGE_PIXEL_COUNT) {
            String errorMessage = "Image size in pixels " + width + "x" + height + " exceeds " + MAX_IMAGE_PIXEL_COUNT + " pixels.  Make the image smaller";
            response.sendError(response.SC_NOT_ACCEPTABLE, errorMessage);
            throw new Exception(errorMessage);
        }

        int pointSize = -1;
        if (getImage.getPradiusPx() != null) {
            pointSize = (int) getImage.getPradiusPx();
        } else {
            pointSize = (int) ((getImage.getDpi() / 25.4) * getImage.getPointRadiusMm());
        }

        String rendering = "ENV=color%3A" + getImage.getPointColour() + "%3Bname%3Acircle%3Bsize%3A" + pointSize
                + "%3Bopacity%3A" + getImage.getPointOpacity();
        if (StringUtils.isNotEmpty(getImage.getEnv())) {
            rendering = "ENV=" + getImage.getEnv();
        }

        //"http://biocache.ala.org.au/ws/webportal/wms/reflect?
        //q=macropus&ENV=color%3Aff0000%3Bname%3Acircle%3Bsize%3A3%3Bopacity%3A1
        //&BBOX=12523443.0512,-2504688.2032,15028131.5936,0.33920000120997&WIDTH=256&HEIGHT=256");
        String speciesAddress = baseWsUrl
                + "/ogc/wms/reflect?"
                + rendering
                + "&SRS=" + getImage.getSrs()
                + "&BBOX=" + getImage.getBbox()
                + "&WIDTH=" + width + "&HEIGHT=" + height
                + "&OUTLINE=" + getImage.isOutline() + "&OUTLINECOLOUR=" + getImage.getOutlineColour();

        //get query parameters
        String q = request.getParameter("q");
        String[] fqs = request.getParameterValues("fq");
        if(!StringUtils.isEmpty(q)){
            speciesAddress = speciesAddress + "&q=" + URLEncoder.encode(q, "UTF-8");
        }
        if(fqs != null && fqs.length != 0){
            for(String fq: fqs){
                speciesAddress = speciesAddress + "&fq=" + URLEncoder.encode(fq, "UTF-8");
            }
        }

        URL speciesURL = new URL(speciesAddress);
        BufferedImage speciesImage = ImageIO.read(speciesURL);

        //"http://spatial.ala.org.au/geoserver/wms/reflect?
        //LAYERS=ALA%3Aworld&SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&STYLES=
        //&FORMAT=image%2Fjpeg&SRS=EPSG%3A900913&BBOX=12523443.0512,-1252343.932,13775787.3224,0.33920000004582&WIDTH=256&HEIGHT=256"
        String layout = "";
        if (!getImage.getScale().equals("off")) {
            layout += "layout:scale";
        }
        String basemapAddress = geoserverUrl + "/wms/reflect?"
                + "LAYERS=ALA%3A" + getImage.getBaseLayer()
                + "&SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&STYLES=" + getImage.getBaselayerStyle()
                + "&FORMAT=image%2Fpng&SRS=" + getImage.getSrs()     //specify the mercator projection
                + "&BBOX=" + getImage.getBbox()
                + "&WIDTH=" + width + "&HEIGHT=" + height + "&OUTLINE=" + getImage.isOutline()
                + "&format_options=dpi:" + getImage.getDpi() + ";" + layout;

        BufferedImage basemapImage;

        if ("roadmap".equalsIgnoreCase(getImage.getBaseMap()) || "satellite".equalsIgnoreCase(getImage.getBaseMap()) ||
                "hybrid".equalsIgnoreCase(getImage.getBaseMap()) || "terrain".equalsIgnoreCase(getImage.getBaseMap())) {
            basemapImage = basemapGoogle(width, height, bboxSRS, getImage.getBaseMap());
        } else {
            basemapImage = ImageIO.read(new URL(basemapAddress));
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D combined = (Graphics2D) img.getGraphics();

        combined.drawImage(basemapImage, 0, 0, Color.WHITE, null);
        //combined.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pointOpacity.floatValue()));
        combined.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        combined.drawImage(speciesImage, null, 0, 0);
        combined.dispose();

        //if filename supplied, force a download
        if (getImage.getFileName() != null) {
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Description", "File Transfer");
            response.setHeader("Content-Disposition", "attachment; filename=" + getImage.getFileName());
            response.setHeader("Content-Transfer-Encoding", "binary");
        } else if (getImage.getFormat().equalsIgnoreCase("png")) {
            response.setContentType("image/png");
        } else {
            response.setContentType("image/jpeg");
        }
        response.setHeader("Cache-Control", wmsCacheControlHeaderPublicOrPrivate + ", max-age=" + wmsCacheControlHeaderMaxAge);
        response.setHeader("ETag", wmsETag.get());

        try {
            if (getImage.getFormat().equalsIgnoreCase("png")) {
                OutputStream os = response.getOutputStream();
                ImageIO.write(img, getImage.getFormat(), os);
                os.close();
            } else {
                //handle jpeg + BufferedImage.TYPE_INT_ARGB
                BufferedImage img2;
                Graphics2D c2;
                (c2 = (Graphics2D) (img2 = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)).getGraphics()).drawImage(img, 0, 0, Color.WHITE, null);
                c2.dispose();
                OutputStream os = response.getOutputStream();
                ImageIO.write(img2, getImage.getFormat(), os);
                os.close();
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private BufferedImage basemapGoogle(int width, int height, double [] extents, String maptype) throws Exception {

        double[] resolutions = {
                156543.03390625,
                78271.516953125,
                39135.7584765625,
                19567.87923828125,
                9783.939619140625,
                4891.9698095703125,
                2445.9849047851562,
                1222.9924523925781,
                611.4962261962891,
                305.74811309814453,
                152.87405654907226,
                76.43702827453613,
                38.218514137268066,
                19.109257068634033,
                9.554628534317017,
                4.777314267158508,
                2.388657133579254,
                1.194328566789627,
                0.5971642833948135};

        //nearest resolution
        int imgSize = 640;
        int gScale = 2;
        double actualWidth = extents[2] - extents[0];
        double actualHeight = extents[3] - extents[1];
        int res = 0;
        while (res < resolutions.length - 1 && resolutions[res + 1] * imgSize > actualWidth
                && resolutions[res + 1] * imgSize > actualHeight) {
            res++;
        }

        int centerX = (int) ((extents[2] - extents[0]) / 2 + extents[0]);
        int centerY = (int) ((extents[3] - extents[1]) / 2 + extents[1]);
        double latitude = SpatialUtils.convertMetersToLat(centerY);
        double longitude = SpatialUtils.convertMetersToLng(centerX);

        //need to change the size requested so the extents match the output extents.
        int imgWidth = (int) ((extents[2] - extents[0]) / resolutions[res]);
        int imgHeight = (int) ((extents[3] - extents[1]) / resolutions[res]);

        String uri = "http://maps.googleapis.com/maps/api/staticmap?";
        String parameters = "center=" + latitude + "," + longitude + "&zoom=" + res + "&scale=" + gScale + "&size=" + imgWidth + "x" + imgHeight + "&maptype=" + maptype;

        BufferedImage img = ImageIO.read(new URL(uri + parameters));

        BufferedImage tmp = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        tmp.getGraphics().drawImage(img, 0, 0, width, height, 0, 0, imgWidth * gScale, imgHeight * gScale, null);

        return tmp;
    }

    /**
     * @return
     * @throws Exception
     */
    private ImgObj wmsCached(WMSTile wco, SpatialSearchRequestParams requestParams,
                             WmsEnv vars, PointType pointType, double[] pbbox,
                             double[] bbox, double[] mbbox, int width, int height, double width_mult,
                             double height_mult, int pointWidth, String[] originalFqs, Set<Integer> hq,
                             String[] boundingBoxFqs, boolean outlinePoints,
                             String outlineColour,
                             HttpServletResponse response,
                             double[] tilebbox, int gridDivisionCount,
                             CoordinateOperation transformFrom4326) throws Exception {

        ImgObj imgObj = null;

        //grid setup
        boolean isGrid = vars.colourMode.equals("grid");
        int divs = gridDivisionCount; //number of x & y divisions in the WIDTH/H    EIGHT
        int[][] gridCounts = isGrid ? new int[divs][divs] : null;
        int xstep = width / divs;
        int ystep = height / divs;

        int x, y;

        //if not transparent and zero size, render dots
        if (vars.alpha > 0 && vars.size > 0) {
            List<float[]> points = wco.getPoints();
            List<int[]> counts = wco.getCounts();
            List<Integer> pColour = wco.getColours();
            if (pColour.size() == 1 && vars.colourMode.equals("-1")) {
                pColour.set(0, vars.colour | (vars.alpha << 24));
            }

            //initialise the image object
            imgObj = ImgObj.create(width, height);

            for (int j = 0; j < points.size(); j++) {

                if (hq != null && hq.contains(j)) {
                    //dont render these points
                    continue;
                }

                float[] ps = points.get(j);

                if (ps == null || ps.length == 0) {
                    continue;
                }

                //for 4326
                double top = tilebbox[3];
                double bottom = tilebbox[1];
                double left = tilebbox[0];
                double right = tilebbox[2];

                if (isGrid) {
                    //render grids
                    int[] count = counts.get(j);

                    //populate grid
                    for (int i = 0; i < ps.length; i += 2) {
                        float lng = ps[i];
                        float lat = ps[i + 1];
                        if (lng >= bbox[0] && lng <= bbox[2]
                                && lat >= bbox[1] && lat <= bbox[3]) {
                            try {
                                GeneralDirectPosition sourceCoords = new GeneralDirectPosition(lng, lat);
                                DirectPosition targetCoords = transformFrom4326.getMathTransform().transform(sourceCoords, null);
                                x = SpatialUtils.scaleLongitudeForImage(targetCoords.getOrdinate(0), left, right, width);
                                y = SpatialUtils.scaleLatitudeForImage(targetCoords.getOrdinate(1), top, bottom, height);

                                if (x >= 0 && x < divs && y >= 0 && y < divs) {
                                    gridCounts[x][y] += count[i / 2];
                                }
                            } catch (MismatchedDimensionException e) {
                            } catch (TransformException e) {
                                // failure to transform a coordinate will result in it not rendering
                            }
                        }
                    }
                } else {
                    renderPoints(vars, bbox, pbbox, width_mult, height_mult, pointWidth, outlinePoints, outlineColour, pColour, imgObj, j, ps, tilebbox, height, width, transformFrom4326);
                }
            }
        }

        //no points
        if (imgObj == null || imgObj.img == null) {
            if (vars.highlight == null) {
                displayBlankImage(response);
                return null;
            }
        } else if (isGrid) {
            //draw grid
            for (x = 0; x < divs; x++) {
                for (y = 0; y < divs; y++) {
                    int v = gridCounts[x][y];
                    if (v > 0) {
                        if (v > 500) {
                            v = 500;
                        }
                        int colour = (((500 - v) / 2) << 8) | (vars.alpha << 24) | 0x00FF0000;
                        imgObj.g.setColor(new Color(colour));
                        imgObj.g.fillRect(x * xstep, y * ystep, xstep, ystep);
                    }
                }
            }
        } else {
            drawUncertaintyCircles(requestParams, vars, height, width, mbbox, bbox, imgObj.g,
                    originalFqs, tilebbox, pointType, transformFrom4326);
        }

        //highlight
        if (vars.highlight != null) {
            imgObj = drawHighlight(requestParams, vars, pointType, width, height, imgObj,
                    originalFqs, boundingBoxFqs, tilebbox, transformFrom4326);
        }

        return imgObj;
    }

    void drawUncertaintyCircles(SpatialSearchRequestParams requestParams, WmsEnv vars, int height, int width,
                                double[] mbbox, double[] bbox, Graphics2D g,
                                String[] originalFqs, double[] tilebbox,
                                PointType pointType, CoordinateOperation transformFrom4326) throws Exception {
        //draw uncertainty circles
        double hmult = (height / (mbbox[3] - mbbox[1]));

        //min uncertainty for current resolution and dot size
        double min_uncertainty = (vars.size + 1) / hmult;

        //for image scaling
        double top = tilebbox[3];
        double bottom = tilebbox[1];
        double left = tilebbox[0];
        double right = tilebbox[2];

        //only draw uncertainty if max radius will be > dot size
        if (vars.uncertainty && MAX_UNCERTAINTY > min_uncertainty) {

            //uncertainty colour/fq/radius, [0]=map, [1]=not specified, [2]=too large
            Color[] uncertaintyColours = {new Color(255, 170, 0, vars.alpha), new Color(255, 255, 100, vars.alpha), new Color(50, 255, 50, vars.alpha)};
            String[] uncertaintyFqs = {"coordinate_uncertainty:[" + min_uncertainty + " TO " + MAX_UNCERTAINTY + "]", "assertions:uncertaintyNotSpecified", "coordinate_uncertainty:[" + MAX_UNCERTAINTY + " TO *]"};
            double[] uncertaintyR = {-1, MAX_UNCERTAINTY, MAX_UNCERTAINTY};

            int originalFqsLength = originalFqs != null ? originalFqs.length : 0;

            String[] fqs = new String[originalFqsLength + 3];

            if(originalFqsLength > 0) {
                System.arraycopy(originalFqs, 0, fqs, 3, originalFqsLength);
            }

            //expand bounding box to cover MAX_UNCERTAINTY radius (m to degrees)
            fqs[1] = "longitude:[" + (bbox[0] - MAX_UNCERTAINTY / 100000.0) + " TO " + (bbox[2] + MAX_UNCERTAINTY / 100000.0) + "]";
            fqs[2] = "latitude:[" + (bbox[1] - MAX_UNCERTAINTY / 100000.0) + " TO " + (bbox[3] + MAX_UNCERTAINTY / 100000.0) + "]";

            requestParams.setPageSize(DEFAULT_PAGE_SIZE);

            for (int j = 0; j < uncertaintyFqs.length; j++) {
                //do not display for [1]=not specified
                if (j == 1) {
                    continue;
                }

                fqs[0] = uncertaintyFqs[j];
                requestParams.setFq(fqs);

                requestParams.setFacet(false);
                requestParams.setPageSize(0);
                requestParams.setFlimit(-1);
                requestParams.setFormattedQuery(null);

                SolrQuery query = ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).searchRequestToSolrQuery(requestParams, null, null);

                // TODO: Is the loss of accuracy using a range facet with n-buckets acceptable? This should be much faster than a terms facet.
                query.add("json.facet", "{facets:%20{%20type:terms,%20field:coordinate_uncertainty,limit:-1,%20facet:{points:%20{type:terms,field:point-0.1,%20limit:-1}}}}");
                QueryResponse qr = ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).query(query, null);

                List<SimpleOrderedMap> termsBuckets = SearchUtils.getList(qr.getResponse(), "facets", "facets", "buckets");

                for (int i = 0; i < termsBuckets.size(); i++) {
                    List<SimpleOrderedMap> pointBuckets = SearchUtils.getList(termsBuckets, i, "buckets");

                    double lng, lat;
                    int x, y;

                    g.setColor(uncertaintyColours[j]);

                    int uncertaintyRadius = (int) Math.ceil(uncertaintyR[j] * hmult);
                    if (uncertaintyR[j] < 0) {
                        uncertaintyRadius = (int) Math.ceil(((Number) termsBuckets.get(i).getVal(0)).doubleValue() * hmult);
                    }

                    for (int k = 0; k < pointBuckets.size(); k++) {
                        String[] lat_lng = pointBuckets.get(k).getVal(0).toString().split(",");

                        lng = Double.parseDouble(lat_lng[1]);
                        lat = Double.parseDouble(lat_lng[0]);

                        try {
                            GeneralDirectPosition sourceCoords = new GeneralDirectPosition(lng, lat);
                            DirectPosition targetCoords = transformFrom4326.getMathTransform().transform(sourceCoords, null);
                            x = SpatialUtils.scaleLongitudeForImage(targetCoords.getOrdinate(0), left, right, width);
                            y = SpatialUtils.scaleLatitudeForImage(targetCoords.getOrdinate(1), top, bottom, height);

                            if (uncertaintyRadius > 0) {
                                g.drawOval(x - uncertaintyRadius, y - uncertaintyRadius, uncertaintyRadius * 2, uncertaintyRadius * 2);
                            } else {
                                g.drawRect(x, y, 1, 1);
                            }
                        } catch (MismatchedDimensionException e) {
                        } catch (TransformException e) {
                            // failure to transform a coordinate will result in it not rendering
                        }
                    }
                }
            }
        }
    }

    ImgObj drawHighlight(SpatialSearchRequestParams requestParams, WmsEnv vars, PointType pointType,
                         int width, int height, ImgObj imgObj, String[] originalFqs, String[] boundingBoxFqs,
                         double[] tilebbox, CoordinateOperation transformFrom4326) throws Exception {
        String[] fqs = new String[3 + (originalFqs != null ? originalFqs.length : 0)];

        if (originalFqs != null) {
            System.arraycopy(originalFqs, 0, fqs, 3, originalFqs.length);
        }

        fqs[0] = vars.highlight;
        fqs[1] = boundingBoxFqs[0];
        fqs[2] = boundingBoxFqs[1];

        requestParams.setFq(fqs);
        requestParams.setFlimit(-1);
        requestParams.setFormattedQuery(null);

        if (imgObj == null || imgObj.img == null) {  //when vars.alpha == 0 img is null
            imgObj = ImgObj.create(width, height);
        }

        ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).streamingQuery(requestParams, null, null, new DrawHighlight(imgObj, vars, tilebbox, width, height, transformFrom4326, this));

        return imgObj;
    }

    /**
     * Returns the wms cache object and initialises it if required.
     *
     * @param vars
     * @param pointType
     * @param requestParams
     * @param bbox
     * @return
     * @throws Exception
     */
    WMSTile getWMSCacheObject(SpatialSearchRequestParams requestParams,
                              WmsEnv vars, PointType pointType,
                              double[] bbox, String[] originalFqs,
                              String[] boundingBoxFqs, boolean canCache) throws Exception {
        // do not cache this query if the cache is disabled or full
        if (wmsCache.isFull() || !wmsCache.isEnabled()) {
            canCache = false;
        }

        //caching is perTile
        String[] origAndBBoxFqs = null;
        if (originalFqs == null || originalFqs.length == 0) {
            origAndBBoxFqs = boundingBoxFqs;
        } else {
            origAndBBoxFqs = new String[originalFqs.length + 2];
            System.arraycopy(originalFqs, 0, origAndBBoxFqs, 2, originalFqs.length);
            origAndBBoxFqs[0] = boundingBoxFqs[0];
            origAndBBoxFqs[1] = boundingBoxFqs[1];
        }

        //replace qid with values for more cache hits
        String qparam = requestParams.getQ();
        if (qparam.startsWith("qid:")) {
            try {
                Qid qid = qidCacheDAO.get(qparam.substring(4));
                if (qid != null) {
                    qparam = qid.getQ() + qid.getWkt() + (qid.getFqs() != null ? StringUtils.join(qid.getFqs(), ",") : "");
                }
            } catch (Exception e) {
            }
        }

        String qfull = qparam + StringUtils.join(requestParams.getFq(), ",") + requestParams.getQc() +
                requestParams.getWkt() + requestParams.getRadius() + requestParams.getLat() + requestParams.getLon();

        //qfull can be long if there is WKT
        String cacheKey = String.valueOf(qfull.hashCode());
        String bboxKey = StringUtils.join(origAndBBoxFqs, ",");

        //grid and -1 colour modes have the same data
        String cm = (vars.colourMode.equals("-1") || vars.colourMode.equals("grid")) ? "-1" : vars.colourMode;

        //if too many points for this zoom level, cache with bbox string
        boolean cacheTileOnly = false;
        Integer count = 0;
        WMSTile wco = null;
        if (canCache) {
            // find cached data at this or higher zoom level
            for (int i = 0; i < PointType.values().length && wco == null; i++) {
                if (PointType.values()[i].getValue() <= pointType.getValue()) {
                    wco = wmsCache.getTest(cacheKey, cm, PointType.values()[i]);
                    if (wco != null) {
                        wco = wmsCache.getTest(cacheKey + bboxKey, cm, PointType.values()[i]);
                    }
                }
            }
            if (wco != null) {
                return wco;
            }

            //count number of unique points for this zoom level
            count = getCachedCount(requestParams, cacheKey + pointType.getLabel(), pointType.getLabel());
            if (count == null || count == 0) {
                wco = new WMSTile();
                wmsCache.put(cacheKey, cm, pointType, wco);
                return wco;
            }

            // too many points to cache the whole layer, just cache the tile
            if (count > wmsCacheMaxLayerPoints && pointType.getValue() > 0) {
                cacheTileOnly = true;
                cacheKey += bboxKey;

                requestParams.setFq(origAndBBoxFqs);
                count = getCachedCount(requestParams, cacheKey, pointType.getLabel());
                requestParams.setFq(originalFqs);
                requestParams.setFormattedQuery(null); // reset formatted query

                if (count == null || count == 0) {
                    wco = new WMSTile();
                    wmsCache.put(cacheKey, cm, pointType, wco);
                    return wco;
                }
            }
        }

        queryFormatUtils.formatSearchQuery(requestParams, false);

        List<LegendItem> colours = null;
        int sz = 0;
        if (canCache) {
            //not found, create it
            requestParams.setFlimit(-1);
            requestParams.setFormattedQuery(null);
            colours = cm.equals("-1") ? null : searchDAO.getColours(requestParams, vars.colourMode);
            sz = colours == null ? 1 : colours.size() + 1;

            // initialize wco
            wco = wmsCache.get(cacheKey, cm, pointType);
        } else {
            wco = new WMSTile();
        }

        //still need colours when cannot cache
        requestParams.setFormattedQuery(null);
        if (colours == null && !cm.equals("-1")) {
            requestParams.setFlimit(-1);
            colours = searchDAO.getColours(requestParams, vars.colourMode);
            sz = colours == null ? 1 : colours.size() + 1;
        }

        //build only once
        synchronized (wco) {
            if (wco.getCached()) {
                return wco;
            }

            // when there is only one colour, return the result for colourMode=="-1"
            if ((colours == null || colours.size() == 1) && !cm.equals("-1")) {
                String prevColourMode = vars.colourMode;
                vars.colourMode = "-1";
                WMSTile equivalentTile = getWMSCacheObject(requestParams, vars, pointType, bbox, originalFqs, boundingBoxFqs, canCache);
                vars.colourMode = prevColourMode;

                //use the correct colour
                List<Integer> pColour = new ArrayList<Integer>(1);
                pColour.add(colours != null ? colours.get(0).getColour() | (vars.alpha << 24) : vars.colour);
                wco.setColours(pColour);

                wco.setBbox(bbox);
                wco.setColourmode(vars.colourMode);
                wco.setCounts(equivalentTile.getCounts());
                wco.setPoints(equivalentTile.getPoints());
                wco.setQuery(cacheKey);
            } else {
                //query with the bbox when it cannot be cached
                if (!canCache || cacheTileOnly) {
                    requestParams.setFq(origAndBBoxFqs);
                    requestParams.setFormattedQuery(null); // reset formatted query
                }

                List<Integer> pColour = new ArrayList<Integer>(sz);
                List<float[]> pointsArrays = new ArrayList<float[]>(sz);
                List<int[]> countsArrays = cm.equals("-1") ? new ArrayList<int[]>(sz) : null;

                queryTile(requestParams, vars, pointType, countsArrays, pointsArrays, colours, pColour, cacheTileOnly);

                wco.setBbox(bbox);
                wco.setColourmode(vars.colourMode);
                wco.setColours(pColour);
                if (cm.equals("-1")) wco.setCounts(countsArrays);
                wco.setPoints(pointsArrays);
                wco.setQuery(cacheKey);
            }

            if (canCache) {
                wmsCache.put(cacheKey, cm, pointType, wco);
            }

            return wco;
        }
    }

    private Integer getCachedCount(SpatialSearchRequestParams requestParams, String countKey, String facet) throws Exception {

        Integer count = null;

        synchronized (countLock) {
            count = (Integer) countsCache.get(countKey);
        }

        if (count == null) {
            requestParams.setFormattedQuery(null);
            requestParams.setFacet(false);
            requestParams.setPageSize(0);

            SolrQuery query = ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).searchRequestToSolrQuery(requestParams, null, null);

            // count unique pointTypes
            query.add("json.facet", "{ result: \"unique(" + facet + ")\"}");
            QueryResponse qr = ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).query(query, null);

            count = (Integer) SearchUtils.getVal(qr.getResponse(), "facets", 1);

            synchronized (countLock) {
                countsCache.put(countKey, count);
            }
        } else {
            // format requestParams
            queryFormatUtils.formatSearchQuery(requestParams, false);
        }

        return count;
    }

    private void queryTile(SpatialSearchRequestParams requestParams, WmsEnv vars, PointType pointType,
                           List<int[]> countsArrays, List<float[]> pointsArrays, List<LegendItem> colours,
                           List<Integer> pColour, boolean cacheTileOnly) throws Exception {

        boolean lastFacetIsAggregate = colours != null && colours.size() == ColorUtil.colourList.length - 1;
        if (lastFacetIsAggregate) {
            pointsArrays.add(null);
            pColour.add(null);
        }

        boolean numericalFacetCategories = vars.colourMode.contains(",");

        //in some instances querying each colour's facet, one by one, is more suitable than pivoting
        requestParams.setFlimit(-1);
        requestParams.setFormattedQuery(null);
        requestParams.setFacet(false);
        requestParams.setPageSize(0);

        SolrQuery query = ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).searchRequestToSolrQuery(requestParams, null, null);

        query.add("json.facet", buildJsonFacet(cacheTileOnly, numericalFacetCategories, colours, vars, pointType));

        QueryResponse qr = ((SearchDAOImpl) ((Advised) searchDAO).getTargetSource().getTarget()).query(query, null);

        SimpleOrderedMap facets = SearchUtils.getMap(qr.getResponse(), "facets");

        if (colours == null || colours.isEmpty() || colours.size() == 1) {
            // There is only a facet f0 when there is only one colour.
            List<SimpleOrderedMap> pointBuckets = SearchUtils.getList(facets, "f0", "buckets");
            makePointsFromBuckets(pointBuckets, pointsArrays, countsArrays);
            if (colours == null || colours.isEmpty()) {
                pColour.add(vars.colour);
            } else {
                pColour.add(colours.get(0).getColour() | (vars.alpha << 24));
            }
        } else {
            //last colour
            int lastColour = ColorUtil.colourList[ColorUtil.colourList.length - 1] | (vars.alpha << 24);

            // buildJsonFacet facets will contain the term facet 'facets' or facet queries 'f[0..n]'
            List<SimpleOrderedMap> missingPointBuckets = SearchUtils.getList(facets, "terms", "missing", "points", "buckets");
            List<SimpleOrderedMap> termsBuckets = SearchUtils.getList(facets, "terms", "buckets");

            for (int i = 0; i < colours.size(); i++) {
                LegendItem li = colours.get(i);

                SimpleOrderedMap facet = (SimpleOrderedMap) facets.get("f" + i);
                List<SimpleOrderedMap> pointBuckets = SearchUtils.getList(facet, "f" + i, "buckets");
                if (pointBuckets != null) {
                    // buildJsonFacet produced facet queries in the same order as colours
                    makePointsFromBuckets(pointBuckets, pointsArrays, countsArrays);
                    pColour.add(li.getColour() | (vars.alpha << 24));
                } else {
                    // buildJsonFacet produced facet terms and missing point buckets. Order is unknown

                    // Is this missing colours?
                    if (li.getFq().endsWith("]")) { // This is set in SearchDAOImpl.getLegend when the facet name is null.
                        makePointsFromBuckets(missingPointBuckets, pointsArrays, countsArrays);
                        pColour.add(li.getColour() | (vars.alpha << 24));
                    } else {
                        // Search through termsBuckets
                        for (int j = 0; j < termsBuckets.size(); j++) {
                            SimpleOrderedMap bucket = termsBuckets.get(j);
                            String name = (String) bucket.get("val");
                            if ((StringUtils.isEmpty(li.getName()) && StringUtils.isEmpty(name))
                                    || (StringUtils.isNotEmpty(li.getName()) && li.getName().equals(name))) {
                                List<SimpleOrderedMap> termPointBuckets = SearchUtils.getList(bucket, "points", "buckets");
                                makePointsFromBuckets(termPointBuckets, pointsArrays, countsArrays);
                                pColour.add(li.getColour() | (vars.alpha << 24));
                                termsBuckets.remove(j);
                                break;
                            }
                        }
                    }
                }
            }

            // ensure all point/colour pairs are added from the term facets
            while (termsBuckets != null && !termsBuckets.isEmpty()) {
                List<SimpleOrderedMap> pointBuckets = SearchUtils.getList(termsBuckets, 0, "points", "buckets");
                makePointsFromBuckets(pointBuckets, pointsArrays, countsArrays);
                pColour.add(lastColour);
                termsBuckets.remove(0);
            }

            if (lastFacetIsAggregate) {
                // A placeholder for the aggregate facet is already created. Use it.
                pColour.set(0, pColour.get(pColour.size() - 1));
                pointsArrays.set(0, pointsArrays.get(pointsArrays.size() - 1));
                pColour.remove(pColour.size() - 1);
                pointsArrays.remove(pointsArrays.size() - 1);
            }
        }
    }

    private String buildJsonFacet(boolean cacheTileOnly, boolean numericalFacetCategories, List<LegendItem> colours, WmsEnv vars, PointType pointType) {

        // get unique pointTypes
        StringBuilder jsonFacet = new StringBuilder("{");
        if (colours == null || colours.size() == 1) {
            jsonFacet.append("f0: { type:terms, field:").append(pointType.getLabel()).append(",limit:-1}");
        } else if (!cacheTileOnly && !numericalFacetCategories) {
            // The query is without a bounding box so the returned facets will be in the same order (count desc)
            // as colours LegendItems.
            int limit = colours.size();
            if (colours.size() == ColorUtil.colourList.length - 1) {
                // The last LegendItem in colours is the 'other' aggregate. Reduce the limit size by 1 set
                // pointsArray[0] to all unique points. These points will be drawn first and overdrawn by
                // subsequent pointsArray lists.
                limit = colours.size() - 1;
            }
            jsonFacet.append("terms: { type:terms, missing: true, field:").append(vars.colourMode).append(", limit:")
                    .append(limit).append(",facet: { points: { type:terms, field:")
                    .append(pointType.getLabel()).append(",limit:-1}}}}");
        } else if (colours.size() >= ColorUtil.colourList.length - 1 || numericalFacetCategories) {
            // Use json.facet with filters > facets exceeds number of colours otherwise the applied bounding box
            // may not return a facet. This is important because of the order that facets is drawn on the tile
            // must be consistent and the order of facets returned varies when the tile bounding box is used.
            List<String> fqsDone = new ArrayList<String>(colours != null ? colours.size() : 0);
            boolean lastFacetIsAggregate = colours.size() == ColorUtil.colourList.length - 1;
            for (int i = 0; i < colours.size(); i++) {
                if (i == 0) jsonFacet.append(",");
                String fq;
                if (i == ColorUtil.colourList.length - 2 && lastFacetIsAggregate) {
                    // This is the facet containing the aggregated colours items. It only exists for the last
                    // LegendItem in colours and when colours.size() is at its largest size possible.
                    fq = StringUtils.join(fqsDone, " AND ");
                } else {
                    LegendItem li = colours.get(i);
                    fq = li.getFq().replace("\"", "\\\\");

                    //invert fq for the 'other' group
                    if (StringUtils.isEmpty(li.getName())) {
                        //li.getFq() is of the form "-(...)"
                        fqsDone.add(li.getFq().substring(1));
                    } else {
                        if (li.getFq().charAt(0) == '-') {
                            fqsDone.add(li.getFq().substring(1));
                        } else {
                            fqsDone.add("-" + li.getFq());
                        }
                    }
                }
                jsonFacet.append("f").append(i).append(":{type:query,limit:-1,q:\"").append(fq)
                        .append("\",facet:{points:{type:terms,limit:-1,field:")
                        .append(pointType.getLabel()).append("}}}");
            }

            jsonFacet.append("}");
        }
        return jsonFacet.toString();
    }

    private void makePointsFromBuckets(List<SimpleOrderedMap> buckets, List<float[]> gPoints, List<int[]> gCount) {
        float[] points = null;
        int[] count = null;

        if (buckets != null && buckets.size() > 0) {
            points = new float[2 * buckets.size()];
            count = new int[buckets.size()];

            int i = 0;
            int j = 0;
            for (SimpleOrderedMap bucket : buckets) {
                // each bucket has a val (String) and a count (Long)
                Iterator<Map.Entry<String, Object>> iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Object> next = iterator.next();
                    if (next.getValue() instanceof String) {
                        String point = (String) next.getValue();
                        if (StringUtils.isNotEmpty(point)) {
                            int p = point.indexOf(',');
                            points[i++] = Float.parseFloat(point.substring(p + 1));
                            points[i++] = Float.parseFloat(point.substring(0, p));
                        } else {
                            points[i++] = Float.NaN;
                            i++;
                            count[j] = 0;
                        }
                    } else {
                        count[j] = 0;
                    }
                }
                j++;
            }
        }

        gPoints.add(points);
        if (gCount != null) gCount.add(count);
    }

    private void renderPoints(WmsEnv vars, double[] bbox, double[] pbbox, double width_mult, double height_mult, int pointWidth, boolean outlinePoints, String outlineColour, List<Integer> pColour, ImgObj imgObj, int j, float[] ps, double[] tilebbox, int height, int width, CoordinateOperation transformFrom4326) throws TransformException {
        int x;
        int y;
        Paint currentFill = new Color(pColour.get(j), true);
        imgObj.g.setPaint(currentFill);
        Color oColour = Color.decode(outlineColour);

        //for 4326
        double top = tilebbox[3];
        double bottom = tilebbox[1];
        double left = tilebbox[0];
        double right = tilebbox[2];

        for (int i = 0; i < ps.length; i += 2) {
            float lng = ps[i];
            float lat = ps[i + 1];

            if (lng >= bbox[0] && lng <= bbox[2]
                    && lat >= bbox[1] && lat <= bbox[3]) {

                try {
                    GeneralDirectPosition sourceCoords = new GeneralDirectPosition(lng, lat);
                    DirectPosition targetCoords = transformFrom4326.getMathTransform().transform(sourceCoords, null);
                    x = SpatialUtils.scaleLongitudeForImage(targetCoords.getOrdinate(0), left, right, width);
                    y = SpatialUtils.scaleLatitudeForImage(targetCoords.getOrdinate(1), top, bottom, height);

                    //System.out.println("Drawing an oval.....");
                    imgObj.g.fillOval(x - vars.size, y - vars.size, pointWidth, pointWidth);
                    if (outlinePoints) {
                        imgObj.g.setPaint(oColour);
                        imgObj.g.drawOval(x - vars.size, y - vars.size, pointWidth, pointWidth);
                        imgObj.g.setPaint(currentFill);
                    }
                } catch (MismatchedDimensionException e) {
                } catch (TransformException e) {
                    // failure to transform a coordinate will result in it not rendering
                }
            }
        }
    }

    public void setTaxonDAO(TaxonDAO taxonDAO) {
        this.taxonDAO = taxonDAO;
    }

    public void setSearchDAO(SearchDAO searchDAO) {
        this.searchDAO = searchDAO;
    }

    public void setSearchUtils(SearchUtils searchUtils) {
        this.searchUtils = searchUtils;
    }

    public void setBaseWsUrl(String baseWsUrl) {
        this.baseWsUrl = baseWsUrl;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public void setOrgCity(String orgCity) {
        this.orgCity = orgCity;
    }

    public void setOrgStateProvince(String orgStateProvince) {
        this.orgStateProvince = orgStateProvince;
    }

    public void setOrgPostcode(String orgPostcode) {
        this.orgPostcode = orgPostcode;
    }

    public void setOrgCountry(String orgCountry) {
        this.orgCountry = orgCountry;
    }

    public void setOrgPhone(String orgPhone) {
        this.orgPhone = orgPhone;
    }

    public void setOrgFax(String orgFax) {
        this.orgFax = orgFax;
    }

    public void setOrgEmail(String orgEmail) {
        this.orgEmail = orgEmail;
    }
}
