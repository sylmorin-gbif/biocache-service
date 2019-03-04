package au.org.ala.biocache.dto;

public class GetFeatureInfo extends GetMap {

    String query_layers = "";
    Double x = 0d;
    Double y = 0d;

    public String getQuery_layers() {
        return query_layers;
    }

    public void setQuery_layers(String query_layers) {
        this.query_layers = query_layers;
    }

    public Double getX() {
        return x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }
}
