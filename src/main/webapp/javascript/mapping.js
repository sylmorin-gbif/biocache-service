var map;

//options
var isFullScreen = false;
var useGoogle = false;

var brokenContentSize;
var extraParams;
var cellButton;
var baseLayerButton;
var fullScreenButton;
var pageUrl;
var fullScreenMapUrl;
var cellUnit; // TODO use Dave's constant vars instead
var popup;
var popupLonLat;

//variables to be set in JSPs
var entityId = '0';
var entityType = '0';
var entityName = '';

/** Constants used to define current selected cell layer size */
var ONE_DEGREE_CELLS = 0;
var CENTI_CELLS = 1;
var TENMILLI_CELLS = 2;

/** variable holding the currently selected density level */
var selectedCellDensity = ONE_DEGREE_CELLS;

/** Is cell selection enabled */
var selectCellToggle = false;

/**
 * Initialise a Open Layers map
 * 
 * @param useGoogle whether to use google projection
 * @return
 */
function initMap(mapDivId, useGoogle){
    var baseLayerButtonTitle;

    if(useGoogle){
        map = createGoogleMap(mapDivId);
        baseLayerButtonTitle = 'Base Layer: switch to default map base layer';
    } else {
        map = create4326Map(mapDivId);
        baseLayerButtonTitle = 'Base Layer: switch to Google map base layer';
    }
    if(!isFullScreen){
    	resizeMap(mapDivId, false);
    }
    //add controls
    zoomButton = new OpenLayers.Control.ZoomBox(
        {title:"Zoom box: zoom on an area by clicking and dragging."});
    mouseDrag = new OpenLayers.Control.Navigation(
        {title:'Drag tool: move the map using the mouse',zoomWheelEnabled:false});
    cellButton = new OpenLayers.Control.Button({
        title:'More Info: click on a cell square to display occurrence counts, etc.',
        displayClass: "selectCellsButton", trigger: toggleSelectCentiCell});
    baseLayerButton = new OpenLayers.Control.Button({
        title: baseLayerButtonTitle, displayClass: "baseLayerButton", trigger: toggleBaseLayer});
    fullScreenButton = new OpenLayers.Control.Button({
        title: 'Fullscreen', displayClass: "fullScreenButton", trigger: toggleFullScreenMap});
    
    var panel = new OpenLayers.Control.Panel({defaultControl:mouseDrag});
    panel.addControls([mouseDrag,zoomButton,cellButton,baseLayerButton,fullScreenButton]);
    map.addControl(panel);
    map.addControl(new OpenLayers.Control.LayerSwitcher());
    map.addControl(new OpenLayers.Control.MousePosition());
    map.addControl(new OpenLayers.Control.ScaleLine());
    map.addControl(new OpenLayers.Control.Navigation({zoomWheelEnabled: false}));
    map.addControl(new OpenLayers.Control.PanZoomBar({zoomWorldIcon: false}));
    
    if(isFullScreen){
    	fullScreenButton.deactivate();
    } else {
    	fullScreenButton.activate();	
    }
}

/**
 * Initialise a map in the standard geoserver projection
 * 
 * @param the map div
 * @return the initialised map
 */
function create4326Map(mapDivId){
    var map = new OpenLayers.Map(mapDivId, {numZoomLevels: 16,controls: []});
    return map;
}

/**
 * Initialise a map in the spherical mercator projection.
 * 
 * @param the map div
 * @return the initialised map
 */
function createGoogleMap(mapDivId){
	
    /**
     * The commercial layers (Google, Virtual Earth, and Yahoo) are
     * in a custom projection - we're calling this Spherical Mercator.
     * GeoServer understands that requests for EPSG:900913 should
     * match the projection for these commercial layers.  Note that
     * this is not a standard EPSG code - so, if you want to load
     * layers from another WMS, it will have to be configured to work
     * with this projection.
     */
    var options = {
        // the "community" epsg code for spherical mercator
        projection: "EPSG:900913",
        // Controls are set in initMap (empty here to prevent double controls appearing)
        controls: [],
        // map horizontal units are meters
        units: "m",
        // this resolution displays the globe in one 256x256 pixel tile
        maxResolution: 156543.0339,
        // these are the bounds of the globe in sperical mercator
        maxExtent: new OpenLayers.Bounds(-20037508, -20037508,
                                          20037508, 20037508)
    };
    // construct a map with the above options
    map = new OpenLayers.Map(mapDivId, options, 
    		{numZoomLevels: 20});
    return map;
}

function getStyle(el, property) {
	  var style;
	  if (el.currentStyle) {
	    style = el.currentStyle[property];
	  } else if( window.getComputedStyle ) {
	    style = document.defaultView.getComputedStyle(el,null).getPropertyValue(property);
	  } else {
	    style = el.style[property];
	  }
	  return style;
}

/**
 * Resize the DIV holding the content.
 * @return
 */
function resizeContent() {
    var content = document.getElementById('content');
    var rightMargin = parseInt(getStyle(content, "right"));
    content.style.width = document.documentElement.clientWidth - content.offsetLeft - rightMargin;
}

/**
 * Resize the Map with the supplied center.
 * @return
 */
function resizeMap(mapDivId, centre) {
    resizeMap(mapDivId);
    if(centre){
      map.setCenter(centre, zoom);
    }
  }

/**
 * Resize the Map.
 * @return
 */
function resizeMap(mapDivId) {
    var centre = map.getCenter();
    var zoom = map.getZoom();
    var sidebar_width = 30;
    if (sidebar_width > 0) {
        sidebar_width = sidebar_width + 5
    }
    document.getElementById(mapDivId).style.left = (sidebar_width) + "px";
    document.getElementById(mapDivId).style.width = (document.getElementById('content').offsetWidth - sidebar_width) + "px";
  }

/**
 * Handle window resizing
 */
function handleResize() {
    if (brokenContentSize) {
      resizeContent(mapDivId);
    }
    resizeMap(mapDivId, true);
}

/**
 * Register an event on the click
 * @return
 */
function toggleSelectCentiCell(){
    zoomButton.deactivate();
    mouseDrag.deactivate();
    
    if (selectCellToggle) {
        // turn off
        selectCellToggle = false;
        cellButton.deactivate();
        map.div.style.cursor =  "default";
        map.events.remove('click');
        removePopup();
    }
    else {
        // turn on
        selectCellToggle = true;
        cellButton.activate();
        map.div.style.cursor =  "pointer";
        map.events.register('click', map, function (e) {
            var lonlat = map.getLonLatFromViewPortPx(e.xy);
            var ONE_DEGREE_CELLS = 0;
            var CENTI_CELLS = 1;
            var TENMILLI_CELLS = 2;
            if(selectedCellDensity==ONE_DEGREE_CELLS){
            	//occurrenceSearch(lonlat.lat, lonlat.lon, 1);
            } else if(selectedCellDensity==CENTI_CELLS){
            	//occurrenceSearch(lonlat.lat, lonlat.lon, 10);
            } else if(selectedCellDensity==TENMILLI_CELLS){
            	//occurrenceSearch(lonlat.lat, lonlat.lon, 100);
            }
            displayCellInfo(lonlat);
        });
    }
}

/**
 * Initialise map layers
 */
function loadLayers(){
    if(!useGoogle){
      map.addLayer(alabaseLayer);
      //map.addLayer(alabaseLayer);
      map.addLayer(blueMarbleLayer);
      //map.addLayer(roadsLayer);
      //map.addLayer(placenamesLayer);
    } else {
        map.addLayers([gsat, gter, gmap, yahoosat, yahooreg, yahoohyb]);
    }

    //useful for debug
    map.addLayer(cellLayer); 
    map.addLayer(centiCellLayer);
    map.addLayer(tenmilliCellLayer);
    // listener for zoom level to choose best cell layer
    map.events.register('zoomend', map, function (e) {
        removePopup();
        cellButton.deactivate();
        mouseDrag.activate();
        var zoom = map.zoom;
        if (zoom < 6) {
            cellUnit = 1.0;
            cellLayer.setVisibility(true);
            centiCellLayer.setVisibility(false);
            tenmilliCellLayer.setVisibility(false);
            selectedCellDensity=ONE_DEGREE_CELLS;
        } else if (zoom >= 6 && zoom < 10) {
            cellUnit = 0.1;
            cellLayer.setVisibility(false);
            centiCellLayer.setVisibility(true);
            tenmilliCellLayer.setVisibility(false);
            selectedCellDensity=CENTI_CELLS;
        } else if (zoom >= 10) {
            cellUnit = 0.01;
            cellLayer.setVisibility(false);
            centiCellLayer.setVisibility(false);
            tenmilliCellLayer.setVisibility(true);
            selectedCellDensity=TENMILLI_CELLS;
        }
    }
    );

    cellButton.events.register('deactivate', this, function (e) {
        toggleSelectCentiCell();
    });
}

/**
 * Zoom to the correct bounds, re-projecting if necessary.
 * Uses the request parameter 'bounds'.
 */
function zoomToBounds(){
    
	// zoom to the correct bounds
    var bounds = null;
    var boundsString = getRequestParameter("bounds");
    
    if (boundsString) {
        bounds = new OpenLayers.Bounds.fromString(getRequestParameter("bounds"));
    } else if (minLongitude!=null) {
    	//defaults have been set in the intialisation of the map
        bounds = new OpenLayers.Bounds();
        bounds.extend(new OpenLayers.LonLat(minLongitude,minLatitude));
        bounds.extend(new OpenLayers.LonLat(maxLongitude,maxLatitude));
    }
    
    if(bounds!=null && useGoogle){
        var proj900913 = new OpenLayers.Projection("EPSG:900913");
        var proj4326 = new OpenLayers.Projection("EPSG:4326");
        //source dest
        bounds = bounds.transform(proj4326, proj900913);
    }
    if(bounds!=null){
    	map.zoomToExtent(bounds, true);
    }
 }

/**
 * Redirects to occurrence search.
 */
function occurrenceSearch(latitude, longitude, roundingFactor) {
	
    if(useGoogle){
        //reproject lat long values
    	var sourceProjection = new OpenLayers.Projection("EPSG:900913");
        var destinationProjection = new OpenLayers.Projection("EPSG:4326");
        var point = new OpenLayers.Geometry.Point(longitude, latitude);
        point.transform(sourceProjection,destinationProjection);
        latitude = point.y;
        longitude = point.x;
    }
	
    // 36 pixels represents 0.1 degrees
    var longMin = (Math.floor(longitude*roundingFactor) )/roundingFactor;
    var latMin = (Math.floor(latitude*roundingFactor) )/roundingFactor;
    var longMax = (Math.ceil(longitude*roundingFactor) )/roundingFactor;
    var latMax = (Math.ceil(latitude*roundingFactor) )/roundingFactor;
    redirectToCell(longMin, latMin, longMax, latMax);
}

function createPopup(response) {
    //document.getElementById(cellInfoDivId).innerHTML = response.responseText;
    if (response.status == 200) {
        //var popupDiv = '<div id="'+cellInfoDivId+'"><img src="${pageContext.request.contextPath}/images/loading.gif" alt="loading..."/></div>';
        popup = new OpenLayers.Popup.AnchoredBubble("cellInfoDivId",popupLonLat,new OpenLayers.Size(150,180),response.responseText,null,true,removePopup);
        map.addPopup(popup);
        popup.setOpacity(0.9);
    }
    else if (response.status == 204) {
        var msg = "No occurrence records found.";
        popup = new OpenLayers.Popup.AnchoredBubble("noData",popupLonLat,new OpenLayers.Size(100,40),msg,null,true,removePopup);
        map.addPopup(popup);
        popup.setOpacity(0.9);
    } else {
        popup = new OpenLayers.Popup.AnchoredBubble("errorMsg",popupLonLat,null,response.responseText,null,true,removePopup);
        map.addPopup(popup);
        popup.setOpacity(0.9);
    }
}

function removePopup() {
    if (popup != null) {
        //popup.destroy();
        //popup=null;
        map.removePopup(popup);
    }
}

/**
 * JS to reload the page with the new baselayer (Geoserver/Google)
 */
function toggleBaseLayer() {
	
	//this is rather inelegant - needs refactoring
	if(isFullScreen){
        var baseUrl = 'fullScreenMap.htm';
        //var argSeparatorIdx = pageUrl.indexOf('?');
        //baseUrl = baseUrl.substring(0, argSeparatorIdx+1);
        //alert('baseUrl: '+baseUrl);
        baseUrl += '?fullScreen=true';
        baseUrl += '&id='+entityId;
        baseUrl += '&type='+entityType;
        baseUrl += '&name='+entityName;
        baseUrl += '&pageUrl='+pageUrl;
        var bounds = getMapBoundsAsCoordinates().toBBOX();
        baseUrl += '&bounds='+bounds;
        if(!useGoogle){
            baseUrl += '&map=google';
        }
        document.location = baseUrl;
        return;
	}
	
    //var centre = map.getCenter().toShortString();
    //centre = centre.replace(/\s+/,""); // remove space after comma
    var bounds = getMapBoundsAsCoordinates().toBBOX(); // e.g. �5,42,10,45�
    //var zoom = map.getZoom();
    var argSeparator = pageUrl.indexOf('?')>0 ? '&' : '?';
    
    var params = "";
    if (bounds) {
        params = "bounds=" + bounds; // + "&zoom=" + zoom;
    }
    if (useGoogle) {
        // switch to WMF
        useGoogle = false;
        baseLayerButton.deactivate();
        window.location.replace( pageUrl + argSeparator + params);
    }
    else {
        // switch to Google
        useGoogle = true;
        baseLayerButton.activate();
        window.location.replace( pageUrl + argSeparator + 'map=google&' + params);
    }
}

/**
 * Gets bounds converting to decimal coordinates is necessary.
 * 
 * @return
 */
function getMapBoundsAsCoordinates(){
	var bounds = map.calculateBounds();
    if(useGoogle) {
    	//alert('using google projection');
        var proj900913 = new OpenLayers.Projection("EPSG:900913");
        var proj4326 = new OpenLayers.Projection("EPSG:4326");
        bounds.transform(proj900913, proj4326);
    }
	return bounds;
}

/**
 * Switch to full screen
 * @return
 */
function toggleFullScreenMap(){
	
	if(!isFullScreen){
	    var baseUrl = fullScreenMapUrl;
	    baseUrl += '&id='+entityId;
	    baseUrl += '&type='+entityType;
	    baseUrl += '&name='+entityName;
	    baseUrl += '&pageUrl='+pageUrl;
	    var bounds = getMapBoundsAsCoordinates().toBBOX();
	    baseUrl += '&bounds='+bounds;
	    if(useGoogle){
	        baseUrl += '&map=google';
	    }
	    //alert(baseUrl);
	    document.location = baseUrl;
	} else {
	    var baseUrl = pageUrl;
	    var bounds = getMapBoundsAsCoordinates().toBBOX();
	    baseUrl += '?bounds='+bounds;
	    if(useGoogle){
	        baseUrl += '&map=google';
	    }
	    document.location = baseUrl;
	}
}

/**
 * Retrieve a request parameter.
 * 
 * @param name
 * @return
 */
function getRequestParameter( name ) {
    // returns the request parameter "value" for the given "name""
    name = name.replace(/[\[]/,"\\\[").replace(/[\]]/,"\\\]");
    var regexS = "[\\?&]"+name+"=([^&#]*)";
    var regex = new RegExp( regexS );
    var results = regex.exec( window.location.href );
    if( results == null )
        return "";
    else
        return results[1];
}
