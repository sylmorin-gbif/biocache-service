package au.org.ala.biocache.dto;


public class GetMap {
    String cqlFilter;
    String env;
    String srs = "EPSG:3857";
    String styles;
    String bbox;
    Integer width = 256;
    Integer height = 256;
    String cache = "default";
    String request;
    boolean outline = true;
    String outlineColour = "0x000000";
    String layers;
    String[] hq;
    Integer gridDetail = 16;

    public String getCqlFilter() {
        return cqlFilter;
    }

    public void setCqlFilter(String cqlFilter) {
        this.cqlFilter = cqlFilter;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getSrs() {
        return srs;
    }

    public void setSrs(String srs) {
        if ("EPSG:900913".equalsIgnoreCase(srs)) {
            this.srs = "EPSG:3857";
        } else {
            this.srs = srs;
        }
    }

    public String getStyles() {
        return styles;
    }

    public void setStyles(String styles) {
        this.styles = styles;
    }

    public String getBbox() {
        return bbox;
    }

    public void setBbox(String bbox) {
        this.bbox = bbox;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getCache() {
        return cache;
    }

    public void setCache(String cache) {
        this.cache = cache;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public boolean isOutline() {
        return outline;
    }

    public void setOutline(boolean outline) {
        this.outline = outline;
    }

    public String getOutlineColour() {
        return outlineColour;
    }

    public void setOutlineColour(String outlineColour) {
        this.outlineColour = outlineColour;
    }

    public String getLayers() {
        return layers;
    }

    public void setLayers(String layers) {
        this.layers = layers;
    }

    public String[] getHq() {
        return hq;
    }

    public void setHq(String[] hq) {
        this.hq = hq;
    }

    public Integer getGridDetail() {
        return gridDetail;
    }

    public void setGridDetail(Integer gridDetail) {
        this.gridDetail = gridDetail;
    }
}
