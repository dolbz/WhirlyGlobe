/*  MaplyController.java
 *  WhirlyGlobeLib
 *
 *  Created by Steve Gifford on 6/2/14.
 *  Copyright 2011-2021 mousebird consulting
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.mousebird.maply;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The MaplyController is the main object in the Maply library when using a 2D map.  
 * Toolkit users add and remove their geometry through here.
 * <p>
 * The controller starts by creating an OpenGL ES surface and handling
 * all the various setup between Maply and that surface.  It also kicks off
 * a LayerThread, which it uses to queue requests to the rest of Maply.
 * <p>
 * Once the controller is set up and running the toolkit user can make
 * calls to add and remove geometry.  Those calls are thread safe.
 * 
 * @author sjg
 *
 */
@SuppressWarnings({"unused","UnusedReturnValue","RedundantSuppression"})
public class MapController extends BaseController implements View.OnTouchListener, Choreographer.FrameCallback
{

	/**
	 * Settings are parameters we need at the very start of the
	 * setup process.
	 */
	public static class Settings extends BaseController.Settings
	{
		/**
		 * Coordinate system to use for the map.
		 */
		public @Nullable CoordSystem coordSys = null;
		/**
		 * Center of the coordinate system.
		 */
		public @Nullable Point3d displayCenter = null;
		/**
		 * Clear color to use for the background.
		 */
		public int clearColor = Color.BLACK;
	}

	/**
	 * Construct with the activity and a coordinate system.  You use this one if you're
	 * using a custom coordinate system.
	 *
	 * @param mainActivity The activity this is part of.
     */
	public MapController(@NotNull Activity mainActivity, @NotNull Settings settings)
	{
		super(mainActivity,settings);

		if (settings.coordSys != null)
			InitCoordSys(settings.coordSys,settings.displayCenter,settings.clearColor);
		else
			Init(settings.clearColor);
	}

	protected void InitCoordSys(CoordSystem coordSys,Point3d displayCenter,int clearColor)
	{
		Mbr mbr = coordSys.getBounds();
		double scaleFactor = 1.0;
		if (mbr != null) {
			// May need to scale this to the space we're expecting
			if (Math.abs(mbr.ur.getX() - mbr.ll.getX()) > 10.0 || Math.abs(mbr.ur.getY() - mbr.ll.getY()) > 10.0) {
				scaleFactor = 4.0 / Math.max (mbr.ur.getX() - mbr.ll.getX(), mbr.ur.getY() - mbr.ll.getY());
			}
		}
		Point3d center;
		if (displayCenter != null)
			center = displayCenter;
		else
			center = new Point3d(0,0,0);
		GeneralDisplayAdapter genCoordAdapter = new GeneralDisplayAdapter(coordSys,coordSys.ll,coordSys.ur,center,new Point3d(scaleFactor,scaleFactor,1.0));

		setupTheRest(genCoordAdapter,clearColor);

		// Set up the bounds
		if (coordAdapter != null) {
			Point2d ll = new Point2d();
			Point2d ur = new Point2d();
			coordAdapter.getGeoBounds(ll, ur);
			setViewExtents(ll, ur);
		}
	}

	/**
	 * Initialize a new map controller with the standard (spherical mercator)
	 * coordinate system.
	 *
     */
	public MapController(@NotNull Activity mainActivity)
	{
		super(mainActivity,null);

		Init(Color.BLACK);
	}

	protected void Init(int clearColor)
	{
		setupTheRest(new CoordSystemDisplayAdapter(new SphericalMercatorCoordSystem()),clearColor);

		// Set up the bounds
		if (coordAdapter != null) {
			Point3d ll = new Point3d(), ur = new Point3d();
			coordAdapter.getBounds(ll, ur);
			// Allow E/W wrapping
			ll.setValue(Float.MAX_VALUE, ll.getY(), ll.getZ());
			ur.setValue(-Float.MAX_VALUE, ur.getY(), ur.getZ());
			setViewExtents(new Point2d(ll.getX(), ll.getY()), new Point2d(ur.getX(), ur.getY()));
		}
	}

	protected void setupTheRest(CoordSystemDisplayAdapter inCoordAdapter,int clearColor)
	{
		coordAdapter = inCoordAdapter;

		// Create the scene and map view
		scene = new Scene(coordAdapter,renderControl);
		mapView = new MapView(this,coordAdapter);
		view = mapView;
		super.setClearColor(clearColor);

		super.Init();

		if (baseView != null)
		{
			baseView.setOnTouchListener(this);
			gestureHandler = new MapGestureHandler(this,baseView);
		}

		// No lights for the map by default
		addPostSurfaceRunnable(this::clearLights);
	}
	
	@Override public void shutdown()
	{
		if (baseView != null) {
			baseView.setOnTouchListener(null);
		}

		Choreographer c = Choreographer.getInstance();
		if (c != null)
			c.removeFrameCallback(this);
		if (mapView != null)
			mapView.cancelAnimation();

		// superclass shuts down the scene

		super.shutdown();

		mapView = null;
		if (gestureHandler != null)
		{
			gestureHandler.shutdown();
		}
		gestureDelegate = null;
		gestureHandler = null;

		if (scene != null) {
			scene.teardownGL();
		}
	}

	// Map version of view
	MapView mapView = null;

	/**
	 * Return the screen coordinate for a given geographic coordinate (in radians).
	 * 
	 * @param geoCoord Geographic coordinate to convert (in radians).
	 * @return Screen coordinate.
	 */
	public Point2d screenPointFromGeo(Point2d geoCoord)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return null;

		return screenPointFromGeo(mapView,geoCoord);
	}
	
	/**
	 * Return the geographic point (radians) corresponding to the screen point.
	 * 
	 * @param screenPt Input point on the screen.
	 * @return The geographic coordinate (radians) corresponding to the screen point.
	 */
	public Point2d geoPointFromScreen(Point2d screenPt)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return null;

		CoordSystemDisplayAdapter coordAdapter = mapView.getCoordAdapter();
		CoordSystem coordSys = coordAdapter.getCoordSystem();
		
		Matrix4d modelMat = mapView.calcModelViewMatrix();
		Point3d dispPt = mapView.pointOnPlaneFromScreen(screenPt, modelMat, renderControl.frameSize, false);
		if (dispPt == null)
			return null;
		Point3d localPt = coordAdapter.displayToLocal(dispPt);
		if (localPt == null)
			return null;
		Point3d geoCoord = coordSys.localToGeographic(localPt);
		if (geoCoord == null)
			return null;
		
		return new Point2d(geoCoord.getX(),geoCoord.getY());
	}
	
	/**
	 * Returns what the user is currently looking at in geographic extents.
	 */
	public Mbr getCurrentViewGeo()
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return null;

		Mbr geoMbr = new Mbr();
		
		Point2d frameSize = renderControl.frameSize;
		geoMbr.addPoint(geoPointFromScreen(new Point2d(0,0)));
		geoMbr.addPoint(geoPointFromScreen(new Point2d(frameSize.getX(),0)));
		geoMbr.addPoint(geoPointFromScreen(new Point2d(frameSize.getX(),frameSize.getY())));
		geoMbr.addPoint(geoPointFromScreen(new Point2d(0,frameSize.getY())));
		
		return geoMbr;
	}
	
	// Convert a geo coord to a screen point
	private Point2d screenPointFromGeo(MapView theMapView,Point2d geoCoord)
	{
		CoordSystemDisplayAdapter coordAdapter = theMapView.getCoordAdapter();
		CoordSystem coordSys = coordAdapter.getCoordSystem();
		Point3d localPt = coordSys.geographicToLocal(new Point3d(geoCoord.getX(),geoCoord.getY(),0.0));
		Point3d dispPt = coordAdapter.localToDisplay(localPt);
		
		Matrix4d modelMat = theMapView.calcModelViewMatrix();
		return theMapView.pointOnScreenFromPlane(dispPt, modelMat, renderControl.frameSize);
	}
	
	boolean checkCoverage(Mbr mbr,MapView theMapView,double height)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return false;

		Point2d centerLoc = mbr.middle();
		Point3d localCoord = theMapView.coordAdapter.coordSys.geographicToLocal(new Point3d(centerLoc.getX(),centerLoc.getY(),0.0));
		theMapView.setLoc(new Point3d(localCoord.getX(),localCoord.getY(),height));
		
		List<Point2d> pts = mbr.asPoints();
		Point2d frameSize = renderControl.frameSize;
		for (Point2d pt : pts)
		{
			Point2d screenPt = screenPointFromGeo(theMapView,pt);
			if (screenPt.getX() < 0.0 || screenPt.getY() < 0.0 || screenPt.getX() > frameSize.getX() || screenPt.getY() > frameSize.getY())
				return false;
		}
		
		return true;
	}

	/**
	 * Get the zoom limits for the map.
	 */
	@Override
	public double getZoomLimitMin()
	{
		return (gestureHandler != null) ? gestureHandler.zoomLimitMin : 0.0;
	}

	/**
	 * Get the zoom limits for the map.
	 */
	@Override
	public double getZoomLimitMax()
	{
		return (gestureHandler != null) ? gestureHandler.zoomLimitMin : Double.POSITIVE_INFINITY;
	}

	/**
	 * Set the zoom limits for the globe.
	 * @param inMin Closest the user is allowed to zoom in.
	 * @param inMax Farthest the user is allowed to zoom out.
	 */
	public void setZoomLimits(final double inMin,final double inMax)
	{
		if (gestureHandler == null) {
			addPostSurfaceRunnable(() -> setZoomLimits(inMin,inMax));
			return;
		}

		gestureHandler.setZoomLimits(inMin,inMax);
	}

	/**
	 * For a given position, how high do we have to be to see the given area.
	 * <p>
	 * Even for 2D maps we represent things in terms of height.
	 * 
	 * @param mbr Bounding box for the area we want to see in geographic (radians).
	 * @param pos Center of the viewing area in geographic (radians).
	 * @return Returns a height for the viewer.
	 */
	public double findHeightToViewBounds(Mbr mbr,Point2d pos)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return 0.0;

		// We'll experiment on a copy of the map view
		MapView newMapView = mapView.clone();
		newMapView.setLoc(new Point3d(pos.getX(),pos.getY(),2.0));
		
		double minHeight = newMapView.minHeightAboveSurface();
		double maxHeight = newMapView.maxHeightAboveSurface();
		
		final boolean minOnScreen = checkCoverage(mbr,newMapView,minHeight);
		final boolean maxOnScreen = checkCoverage(mbr,newMapView,maxHeight);
		
		// No idea, just give up
		if (minOnScreen) {
			return minHeight;
		} else if (!maxOnScreen) {
			return mapView.getLoc().getZ();
		}

		// Do a binary search between the two heights
		final double minRange = 1e-5;
		while (minRange <= maxHeight - minHeight) {
			final double midHeight = (minHeight + maxHeight) / 2.0;
			if (checkCoverage(mbr, newMapView, midHeight)) {
				maxHeight = midHeight;
			} else {
				minHeight = midHeight;
			}
		}
		
		return maxHeight;
	}

	/**
	 * Set the current view position.
	 * @param pt Horizontal location of the center of the screen in geographic radians (not degrees).
	 * @param z Height above the map in display units.
	 */
	public void setPositionGeo(final Point2d pt,final double z)
	{
		setPositionGeo(pt.getX(), pt.getY(), z);
	}

	/**
	 * Set the current view position.
	 * @param pt Location of the center of the screen in geographic radians (not degrees), z = height
	 */
	public void setPositionGeo(final Point3d pt)
	{
		setPositionGeo(pt.getX(), pt.getY(), pt.getZ());
	}

	/**
	 * Set the current view position.
	 * @param x Horizontal location of the center of the screen in geographic radians (not degrees).
	 * @param y Vertical location of the center of the screen in geographic radians (not degrees).
	 * @param z Height above the map in display units.
	 */
	public void setPositionGeo(final double x,final double y,final double z)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return;

		if (!rendererAttached) {
			addPostSurfaceRunnable(() -> setPositionGeo(x,y,z));
			return;
		}

		mapView.cancelAnimation();
		Point3d geoCoord = mapView.coordAdapter.coordSys.geographicToLocal(new Point3d(x,y,0.0));
		mapView.setLoc(new Point3d(geoCoord.getX(),geoCoord.getY(),z));
	}

	/**
	 * Return the position in lat/lon in radians.
	 * Height is height above the plane, which around M_PI in size.
     */
	public Point3d getPositionGeo()
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return null;

		Point3d loc = mapView.getLoc();
		if (loc == null)
			return null;
		Point3d geoLoc = mapView.coordAdapter.coordSys.localToGeographic(loc);
		return new Point3d(geoLoc.getX(),geoLoc.getY(),loc.getZ());
	}

	/**
	 * Animate to a new view position
	 * @param x Horizontal location of the center of the screen in geographic radians (not degrees).
	 * @param y Vertical location of the center of the screen in geographic radians (not degrees).
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final double x,final double y,final double howLong)
	{
		if (mapView != null) {
			animatePositionGeo(x, y, null, null, howLong);
		}
	}

	/**
	 * Animate to a new view position
	 * @param x Horizontal location of the center of the screen in geographic radians (not degrees).
	 * @param y Vertical location of the center of the screen in geographic radians (not degrees).
	 * @param z Height above the map in display units.
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final double x,final double y,final double z,final double howLong)
	{
		if (mapView != null) {
			animatePositionGeo(x, y, z, null, howLong);
		}
	}

	/**
	 * Animate to a new view position
	 * @param x Horizontal location of the center of the screen in geographic radians (not degrees).
	 * @param y Vertical location of the center of the screen in geographic radians (not degrees).
	 * @param z Height above the map in display units.
	 * @param rot Map rotation in radians
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final double x,final double y,final Double z,final Double rot,final double howLong)
	{
		animatePositionGeo(new Point3d(x,y,(z != null)?z:0.0),rot,howLong);
	}

	/**
	 * Animate to a new view position
	 * @param loc Horizontal location of the center of the screen in geographic radians (not degrees).
	 * @param z Height above the map in display units.
	 * @param rot Map rotation in radians
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final Point2d loc,final Double z,final Double rot,final double howLong)
	{
		animatePositionGeo(new Point3d(loc.getX(),loc.getY(),(z != null)?z:0.0),rot,howLong);
	}

	/**
	 * Animate to a new view position
	 * @param targetGeoLoc Location of the center of the screen in geographic radians (not degrees). z = height above the map in display units.
	 * @param rot Map rotation in radians
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final Point3d targetGeoLoc,final Double rot,final double howLong)
	{
		if (targetGeoLoc == null || !running || mapView == null || renderWrapper == null ||
				renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return;

		if (!rendererAttached) {
			addPostSurfaceRunnable(() -> animatePositionGeo(targetGeoLoc,rot,howLong));
			return;
		}

		Point3d localCoord = mapView.coordAdapter.coordSys.geographicToLocal(targetGeoLoc);
		Point3d newPoint = new Point3d(localCoord.getX(),localCoord.getY(), targetGeoLoc.getZ());
		MapAnimateTranslate dg = new MapAnimateTranslate(mapView, renderControl, newPoint, rot,
		                                                 (float)howLong, viewBounds, zoomAnimationEasing);

		mapView.cancelAnimation();
		mapView.setAnimationDelegate(dg);
	}

	/**
	 * Animate to a new location, placing that location at a specified position on the screen relative to the normal center position
	 * @param geoLoc Location in geographic radians (not degrees), z = height in display units
	 * @param offset The offset from the viewport center
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final Point3d geoLoc,final Point2d offset,final double howLong) {
		animatePositionGeo(geoLoc,offset,null,howLong);
	}

	/**
	 * Animate to a new location, placing that location at a specified position on the screen relative to the normal center position
	 * @param targetGeoLoc Location in geographic radians (not degrees), z = height in display units
	 * @param offset The offset from the viewport center
	 * @param targetRot Map rotation in radians
	 * @param howLong Time (in seconds) to animate.
	 */
	public void animatePositionGeo(final Point3d targetGeoLoc,final Point2d offset,final Double targetRot,final double howLong)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return;

		if (!rendererAttached) {
			addPostSurfaceRunnable(() -> animatePositionGeo(targetGeoLoc,offset,targetRot,howLong));
			return;
		}

		mapView.cancelAnimation();

		// save current view state
		Point3d curLoc = mapView.getLoc();
		double curRot = mapView.getRot();

		CoordSystemDisplayAdapter coordAdapter = mapView.coordAdapter;
		CoordSystem coordSys = (coordAdapter != null) ? coordAdapter.coordSys : null;
		if (curLoc == null || coordSys == null) {
		  return;
		}

		// Convert to local
		Point3d targetLocalCoord = coordSys.geographicToLocal(targetGeoLoc);
		// The height has been discarded, reset it
		targetLocalCoord.setValue(targetLocalCoord.getX(), targetLocalCoord.getY(), targetGeoLoc.getZ());
		// Go there
		mapView.setLoc(targetLocalCoord,false);
		if (targetRot != null) {
			mapView.setRot(targetRot);
		}

		// Find the location of the offset point in the new state
		Point2d geoCoord = geoPointFromScreen(getFrameSize().multiplyBy(0.5).addTo(offset));
		// Fix z

		// todo: check if within bounds
//    nextState.pos = MaplyCoordinateDMakeWithMaplyCoordinate(geoCoord);
//    [self setViewStateInternal:nextState runViewUpdates:false];
//    bool valid = [self withinBounds:oldLoc view:wrapView renderer:sceneRenderer mapView:mapView newCenter:&newCenter];

		// restore current view state
		mapView.setLoc(curLoc,false);
		mapView.setRot(curRot);

		animatePositionGeo(geoCoord.getX(), geoCoord.getY(), targetGeoLoc.getZ(), targetRot, howLong);
	}

	/**
	 * Set the heading for the current visual.  0 is due north.
     */
	public void setHeading(final double heading)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return;

		if (!rendererAttached) {
			addPostSurfaceRunnable(() -> setHeading(heading));
			return;
		}

		mapView.cancelAnimation();
		mapView.setRot(heading);
	}

	/**
	 * Return the current heading.  0 is due north.
     */
	public double getHeading()
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return 0.0;

		return mapView.getRot();
	}

	/**
	 * Return the current height
	 */
	public double getHeight()
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return 0.0;

		Point3d loc = mapView.getLoc();
		return (loc != null) ? loc.getZ() : 0.0;
	}

	/**
	 * Set the current height in display units
	 */
	public void setHeight(final double height)
	{
		if (!running || mapView == null || renderWrapper == null || renderWrapper.maplyRender == null || renderControl.frameSize == null)
			return;

		if (!rendererAttached) {
			addPostSurfaceRunnable(() -> setHeight(height));
			return;
		}

		Point3d curLoc = mapView.getLoc();
		if (curLoc != null) {
			mapView.setLoc(new Point3d(curLoc.getX(), curLoc.getY(), height), true);
		}
	}

	public boolean getAllowRotateGesture() {
		return (running && gestureHandler != null && gestureHandler.allowRotate);
	}

	/**
	 * If set we'll allow the user to rotate.
	 * If not, we'll keep north up at all times.
     */
	public void setAllowRotateGesture(boolean allowRotate)
	{
		if (running && gestureHandler != null) {
			gestureHandler.allowRotate = allowRotate;
		}

	}

	public boolean getAllowZoom() {
		return (running && gestureHandler != null && gestureHandler.allowZoom);
	}

	/**
	 * If set, the user can zoom in and out.
	 * If not set, they can't.  On by default.
	 */
	public void setAllowZoom(boolean allowZoom) {
		if (running && gestureHandler != null) {
			gestureHandler.allowZoom = allowZoom;
		}
	}

	public boolean getAllowPan() {
		return (running && gestureHandler != null && gestureHandler.allowPan);
	}

	/**
	 * If set, the user can pan around.
	 * If not set, they can't.  On by default.
	 */
	public void setAllowPan(boolean allowPan) {
		if (running && gestureHandler != null) {
			gestureHandler.allowPan = allowPan;
		}
	}
	
	// Gesture handler
	MapGestureHandler gestureHandler = null;
	
	/**
	 * Use this delegate when you want user interface feedback from the maply controller.
	 * 
	 * @author sjg
	 *
	 */
	public interface GestureDelegate
	{
		/**
		 * The user selected the given object.  Up to you to figure out what it is.
		 * 
		 * @param mapControl The maply controller this is associated with.
		 * @param selObjs The objects the user selected (e.g. MaplyScreenMarker).
		 * @param loc The location they tapped on.  This is in radians.
		 * @param screenLoc The location on the OpenGL surface.
		 */
		void userDidSelect(MapController mapControl,SelectedObject[] selObjs,Point2d loc,Point2d screenLoc);
		
		/**
		 * The user tapped somewhere, but not on a selectable object.
		 * 
		 * @param mapControl The maply controller this is associated with.
		 * @param loc The location they tapped on.  This is in radians.
		 * @param screenLoc The location on the OpenGL surface.
		 */
		void userDidTap(MapController mapControl,Point2d loc,Point2d screenLoc);

		/**
		 * The user long pressed somewhere, either on a selectable object or nor
		 * @param mapController The maply controller this is associated with.
		 * @param selObjs The objects (e.g. MaplyScreenMarker) that the user long pressed or null if there was none
		 * @param loc The location they tapped on.  This is in radians.
         * @param screenLoc The location on the OpenGL surface.
         */
		void userDidLongPress(MapController mapController, SelectedObject[] selObjs, Point2d loc, Point2d screenLoc);

		/**
		 * Called when the map first starts moving.
		 *
		 * @param mapControl The map controller this is associated with.
		 * @param userMotion Set if the motion was caused by a gesture.
		 */
		void mapDidStartMoving(MapController mapControl, boolean userMotion);

		/**
		 * Called when the map stops moving.
		 *
		 * @param mapControl The map controller this is associated with.
		 * @param corners Corners of the viewport.  If one of them is null, that means it doesn't land anywhere valid.
		 * @param userMotion Set if the motion was caused by a gesture.
		 */
		void mapDidStopMoving(MapController mapControl, Point3d[] corners, boolean userMotion);

		/**
		 * Called for every single visible frame of movement.  Be careful what you do in here.
		 *
		 * @param mapControl The map controller this is associated with.
		 * @param corners Corners of the viewport.  If one of them is null, that means it doesn't land anywhere valid.
		 * @param userMotion Set if the motion was caused by a gesture.
		 */
		void mapDidMove(MapController mapControl, Point3d[] corners, boolean userMotion);
	}

	/**
	 * Set the gesture delegate to get callbacks when the user taps somewhere.
	 */
	public GestureDelegate gestureDelegate = null;
	
	// Called by the gesture handler to let us know the user tapped
	// screenLoc is in view coordinates
	public void processTap(final Point2d screenLoc) {
		final GestureDelegate delegate = running ? gestureDelegate : null;
		if (gestureDelegate == null) {
			return;
		}

		final Matrix4d mapTransform = mapView.calcModelViewMatrix();
		final Point3d loc = mapView.pointOnPlaneFromScreen(screenLoc, mapTransform, getViewSize(), false);

		final Point3d localPt = mapView.getCoordAdapter().displayToLocal(loc);
		Point3d geoPt = null;
		if (localPt != null) {
			geoPt = mapView.getCoordAdapter().getCoordSystem().localToGeographic(localPt);
		}

		if (geoPt != null) {
			final SelectedObject[] selObjs = this.getObjectsAtScreenLoc(screenLoc, vectorSelectDistance);

			if (selObjs != null) {
				gestureDelegate.userDidSelect(this, selObjs, geoPt.toPoint2d(), screenLoc);
			} else {
				// Just a simple tap, then
				gestureDelegate.userDidTap(this, geoPt.toPoint2d(), screenLoc);
			}
		}
	}

	/**
	 * Called by the gesture handler to let us know the user long pressed somewhere
	 * @param screenLoc Screen coordinates of the press
     */
    public void processLongPress(final Point2d screenLoc) {
		final GestureDelegate delegate = running ? gestureDelegate : null;
		if (delegate == null) {
			return;
		}

		final Matrix4d mapTransform = mapView.calcModelViewMatrix();
		final Point3d loc = mapView.pointOnPlaneFromScreen(screenLoc, mapTransform, renderControl.frameSize, false);
		final Point3d localPt = mapView.getCoordAdapter().displayToLocal(loc);
		Point3d geoPt = null;
		if (localPt != null) {
			geoPt = mapView.getCoordAdapter().getCoordSystem().localToGeographic(localPt);
		}
		if (geoPt != null) {
			final SelectedObject[] selObjs = this.getObjectsAtScreenLoc(screenLoc, vectorSelectDistance);
			delegate.userDidLongPress(this, selObjs, geoPt.toPoint2d(), screenLoc);
		}
	}

	// Pass the touches on to the gesture handler
	@Override
	public boolean onTouch(View view, MotionEvent e) {
		view.performClick();
		final MapGestureHandler handler = running ? gestureHandler : null;
		return handler != null && handler.onTouch(view, e);
	}

    boolean isPanning = false, isZooming = false, isRotating = false,
			isAnimating = false, isUserMotion = false, isFinalMotion = false;
    
    public void panDidStart(boolean userMotion) { handleStartMoving(userMotion); isPanning = true; }
    public void panDidEnd(boolean userMotion) { isPanning = false; handleStopMoving(userMotion); }
    public void zoomDidStart(boolean userMotion) { handleStartMoving(userMotion); isZooming = true; }
    public void zoomDidEnd(boolean userMotion) { isZooming = false; handleStopMoving(userMotion); }
    public void rotateDidStart(boolean userMotion) { handleStartMoving(userMotion); isRotating = true; }
    public void rotateDidEnd(boolean userMotion) { isRotating = false; handleStopMoving(userMotion); }
    
    /**
     * Called by the gesture handler to filter out start motion events.
     *
     * @param userMotion Set if kicked off by user motion.
     */
    public void handleStartMoving(boolean userMotion)
    {
		final RendererWrapper wrapper = renderWrapper;
		final GestureDelegate delegate = gestureDelegate;
		if (delegate != null) {
			if (!userMotion && (isPanning || isRotating || isZooming || isAnimating)) {
				// Transitioning from user motion to animation, e.g., for a fling
				delegate.mapDidStartMoving(this, isUserMotion);
				delegate.mapDidStartMoving(this, false);
			} else if (!isPanning && !isRotating && !isZooming && !isAnimating) {
				delegate.mapDidStartMoving(this, userMotion);

				final Choreographer c = Choreographer.getInstance();
				if (c != null) {
					c.removeFrameCallback(this);
					c.postFrameCallback(this);
				}
			}
		}

        isUserMotion = userMotion;
		isAnimating = !userMotion;
    }
    
    /**
     * Called by the gesture handler to filter out end motion events.
     *
     * @param userMotion Set if kicked off by user motion.
     */
    public void handleStopMoving(boolean userMotion)
    {
        if (renderWrapper == null || renderWrapper.maplyRender == null)
            return;

		final GestureDelegate delegate = gestureDelegate;
		if (!isPanning && !isRotating && !isZooming && delegate != null) {
			// Notify stopping after the next frame callback
			isFinalMotion = true;
		}

		isAnimating = false;
    }
    
    double lastViewUpdate = 0.0;
    /**
     * Frame callback for the Choreographer
     */
    public void doFrame(long frameTimeNanos)
    {
        if (mapView != null) {
            double newUpdateTime = mapView.getLastUpdatedTime();
			final GestureDelegate delegate = gestureDelegate;
			Point3d[] corners = null;
            if (delegate != null && lastViewUpdate < newUpdateTime) {
                corners = getVisibleCorners();
				delegate.mapDidMove(this, corners, isUserMotion);
                lastViewUpdate = newUpdateTime;
            }
            if (isFinalMotion) {
            	if (delegate != null) {
            		if (corners == null) {
						corners = getVisibleCorners();
					}
					delegate.mapDidStopMoving(this,corners,isUserMotion);
				}
            	isFinalMotion = false;
			}
        }
        
        Choreographer c = Choreographer.getInstance();
        if (c != null) {
            c.removeFrameCallback(this);
            c.postFrameCallback(this);
        }
    }
    
    
    /**
     * Calculate visible corners for what's currently being seen.
     * If the eye point is too high, expect null corners.
     * @return Array of coordinates
     */
    public Point3d[] getVisibleCorners()
    {
		if (!running || mapView == null)
			return null;

		final Point2d frameSize = renderControl.frameSize;
        final Point2d[] screenCorners = new Point2d[]{
			new Point2d(0.0, 0.0),
			new Point2d(frameSize.getX(), 0.0),
			new Point2d(frameSize.getX(), frameSize.getY()),
			new Point2d(0.0, frameSize.getY())
        };
        
        final Matrix4d modelMat = mapView.calcModelViewMatrix();
        
        final CoordSystemDisplayAdapter coordAdapter = mapView.getCoordAdapter();
        if (coordAdapter == null || renderWrapper == null || renderWrapper.maplyRender == null ||
				renderControl.frameSize == null) {
			return null;
		}
        final CoordSystem coordSys = coordAdapter.getCoordSystem();
        if (coordSys == null) {
			return null;
		}

		final Point3d[] retCorners = new Point3d[4];
        for (int ii=0;ii<4;ii++)
        {
			final Point3d planePt = mapView.pointOnPlaneFromScreen(screenCorners[ii],modelMat,frameSize,false);
            if (planePt != null)
                retCorners[ii] = coordSys.localToGeographic(coordAdapter.displayToLocal(planePt));
        }
        
        return retCorners;
    }

}
