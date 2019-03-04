package au.org.ala.biocache.dto;

public class GetLegendGraphic extends GetFeatureInfo {
    String style = "";

    public GetLegendGraphic() {
        // defaults
        setWidth(30);
        setHeight(20);
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }
}
