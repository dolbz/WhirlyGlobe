/* RenderController.java
 * AutoTesterAndroid.maply
 *
 * Created by Tim Sylvester on 24/03/2021
 * Copyright © 2021 mousebird consulting, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.mousebird.maply;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

import static javax.microedition.khronos.egl.EGL10.EGL_DRAW;
import static javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT;
import static javax.microedition.khronos.egl.EGL10.EGL_READ;

/**
 * The Render Controller handles the object manipulation and rendering interface.
 * It can be a standalone object, doing offline rendering or it can be attached
 * to a BaseController and used to manage that controller's rendering.
 */
@SuppressWarnings({"unused", "UnusedReturnValue", "RedundantSuppression"})
public class RenderController implements RenderControllerInterface
{
    public static final String kToolkitDefaultTriangleNoLightingProgram = "Default Triangle;lighting=no";

    // Draw priority defaults
    public static final int StarsDrawPriorityDefault = 0;                 // kMaplyStarsDrawPriorityDefault
    public static final int SunDrawPriorityDefault = 2;                   // kMaplySunDrawPriorityDefault
    public static final int MoonDrawPriorityDefault = 3;                  // kMaplyMoonDrawPriorityDefault
    public static final int AtmosphereDrawPriorityDefault = 10;           // kMaplyAtmosphereDrawPriorityDefault
    /// Where we start image layer draw priorities
    public static final int ImageLayerDrawPriorityDefault = 100;          // kMaplyImageLayerDrawPriorityDefault
    /// We'll start filling in features right around here
    public static final int FeatureDrawPriorityBase = 20000;              // kMaplyFeatureDrawPriorityBase
    public static final int StickerDrawPriorityDefault = 30000;           // kMaplyStickerDrawPriorityDefault
    public static final int MarkerDrawPriorityDefault = 40000;            // kMaplyMarkerDrawPriorityDefault
    public static final int VectorDrawPriorityDefault = 50000;            // kMaplyVectorDrawPriorityDefault
    public static final int ParticleSystemDrawPriorityDefault = 55000;    // kMaplyParticleSystemDrawPriorityDefault
    public static final int LabelDrawPriorityDefault = 60000;             // kMaplyLabelDrawPriorityDefault
    public static final int LoftedPolysDrawPriorityDefault = 70000;       // kMaplyLoftedPolysDrawPriorityDefault
    public static final int ShapeDrawPriorityDefault = 80000;             // kMaplyShapeDrawPriorityDefault
    public static final int BillboardDrawPriorityDefault = 90000;         // kMaplyBillboardDrawPriorityDefault
    public static final int ModelDrawPriorityDefault = 100000;            // kMaplyModelDrawPriorityDefault
    // Unlikely to have any draw priorities here or beyond.
    public static final int MaxDrawPriorityDefault = 100100;              // kMaplyMaxDrawPriorityDefault

    Point2d frameSize = new Point2d(0.0, 0.0);

    // Represents an ID that doesn't have data associated with it
    public static long EmptyIdentity = 0;

    /**
     * Enumerated values for image types.
     */
    public enum ImageFormat {
        MaplyImageIntRGBA,
        MaplyImageUShort565,
        MaplyImageUShort4444,
        MaplyImageUShort5551,
        MaplyImageUByteRed,MaplyImageUByteGreen,MaplyImageUByteBlue,MaplyImageUByteAlpha,
        MaplyImageUByteRGB,
        MaplyImageETC2RGB8,MaplyImageETC2RGBA8,MaplyImageETC2RGBPA8,
        MaplyImageEACR11,MaplyImageEACR11S,MaplyImageEACRG11,MaplyImageEACRG11S,
        MaplyImage4Layer8Bit
    }

    /**
     * If set, we'll explicitly call dispose on any objects that were
     * being kept around for selection.
     */
    public boolean disposeAfterRemoval = false;

    // Scene stores the objects
    public Scene scene = null;
    public CoordSystemDisplayAdapter coordAdapter = null;

    /**
     * Return the current scene.  Only for sure within the library.
     */
    public Scene getScene()
    {
        return scene;
    }

    /**
     * Return the current coordinate system.
     */
    public CoordSystem getCoordSystem() { return coordAdapter.coordSys; }

    /**
     * This constructor assumes we'll be hooking up to surface provided later.
     */
    RenderController() {
        initialise();
    }

    // Set if this is a standalone renderer
    protected boolean offlineMode = false;

    /**
     * If true, the renderer was set up as offline.
     */
    public boolean getOfflineMode() {
        return offlineMode;
    }

    private static final int[] glAttribList = {
        BaseController.EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL10.EGL_NONE
    };
    private static final int[] glSurfaceAttrs = {
        EGL10.EGL_WIDTH, 32,
        EGL10.EGL_HEIGHT, 32,
        EGL10.EGL_NONE
    };

    // Construct a new render control based on an existing one
    public RenderController(RenderController baseControl,int width,int height)
    {
        offlineMode = true;
        frameSize = new Point2d(width,height);
        setConfig(baseControl, null);

        if (config == null) {
            // eglCreateContext will fail, so fail early with a more helpful message
            throw new InvalidParameterException("No OpenGL ES configuration was selected");
        }

        // Set up our own EGL context for offline work
        EGL10 egl = (EGL10) EGLContext.getEGL();

        offlineGLContext = new ContextInfo(display, null, null, null);
        offlineGLContext.eglContext = egl.eglCreateContext(display, config, context, glAttribList);
        if (LayerThread.checkGLError(egl, "eglCreateContext") || offlineGLContext.eglContext == null) {
            throw new RuntimeException("Failed to create OpenGLES context");
        }

        offlineGLContext.eglDrawSurface = egl.eglCreatePbufferSurface(display, config, glSurfaceAttrs);
        offlineGLContext.eglReadSurface = offlineGLContext.eglDrawSurface;
        if (LayerThread.checkGLError(egl, "eglCreatePbufferSurface") || offlineGLContext.eglDrawSurface == null) {
            egl.eglDestroyContext(display, offlineGLContext.eglContext);
            throw new RuntimeException("Failed to create OpenGLES surface");
        }

        // Need a task manager that just runs things on the current thread
        //  after setting the proper context for rendering
        TaskManager ourTaskMan = (run,mode) -> {
            ContextInfo savedContext = setEGLContext(offlineGLContext);
            if (savedContext != null) {
                try {
                    run.run();
                } finally {
                    setEGLContext(savedContext);
                }
            }
        };

        ourTaskMan.addTask(() -> {
            initialise(width, height);

            // Set up a pass-through coordinate system, view, and so on
            final CoordSystem coordSys = new PassThroughCoordSystem();
            final Point3d ll = new Point3d(0.0, 0.0, 0.0);
            final Point3d ur = new Point3d(width, height, 0.0);
            final Point3d scale = new Point3d(1.0, 1.0, 1.0);
            final Point3d center = new Point3d((ll.getX() + ur.getX()) / 2.0,
                                            (ll.getY() + ur.getY()) / 2.0,
                                            (ll.getZ() + ur.getZ()) / 2.0);
            coordAdapter = new GeneralDisplayAdapter(coordSys, ll, ur, center, scale);
            final FlatView flatView = new FlatView(null, coordAdapter);
            final Mbr extents = new Mbr(new Point2d(ll.getX(), ll.getY()),
                                        new Point2d(ur.getX(), ur.getY()));
            flatView.setExtents(extents);
            flatView.setWindow(new Point2d(width, height), new Point2d(0.0, 0.0));
            view = flatView;

            scene = new Scene(coordAdapter, this);

            // This will properly wire things up
            Init(scene, coordAdapter, ourTaskMan);
            setScene(scene);
            setView(view);

            // Need all the default shaders
            setupShadersNative();
        }, ThreadMode.ThreadCurrent);
    }

    /**
     * This constructor sets up its own render target.  Used for offline rendering.
     */
    public RenderController(int width,int height)
    {
        this(null,width,height);
    }

    /**
     * We don't want to deal with threads and such down here, so
     * the controller one level up gives us an addTask method
     * to hand over the runnables.
     */
    public interface TaskManager {
        void addTask(Runnable run,ThreadMode mode);
    }

    TaskManager taskMan = null;

    public void Init(Scene inScene,CoordSystemDisplayAdapter inCoordAdapter,TaskManager inTaskMan)
    {
        scene = inScene;
        coordAdapter = inCoordAdapter;
        taskMan = inTaskMan;

        // Fire up the managers.  Can't do anything without these.
        vecManager = new VectorManager(scene);
        loftManager = new LoftedPolyManager(scene);
        wideVecManager = new WideVectorManager(scene);
        markerManager = new MarkerManager(scene);
        stickerManager = new StickerManager(scene);
        labelManager = new LabelManager(scene);
        layoutManager = new LayoutManager(scene);
        selectionManager = new SelectionManager(scene);
        componentManager = new ComponentManager(scene);
        particleSystemManager = new ParticleSystemManager(scene);
        shapeManager = new ShapeManager(scene);
        billboardManager = new BillboardManager(scene);
        geomManager = new GeometryManager(scene);
    }

    View view = null;
    public void setView(View inView)
    {
        view = inView;
        setViewNative(inView);
    }

    final ArrayList<ActiveObject> activeObjects = new ArrayList<>();

    /**
     * Add an active object that will be called right before the render (on the render thread).
     */
    public void addActiveObject(ActiveObject activeObject)
    {
        synchronized (activeObjects) {
            activeObjects.add(activeObject);
        }
    }

    /**
     * Add an active object to the beginning of the list.  Do this if you want to make sure
     * yours is run first.
     */
    public void addActiveObjectAtStart(ActiveObject activeObject) {
        synchronized (activeObjects) {
            activeObjects.add(0,activeObject);
        }
    }

    /**
     * Remove an active object added earlier.
     */
    public void removeActiveObject(ActiveObject activeObject)
    {
        synchronized (activeObjects) {
            activeObjects.remove(activeObject);
        }
    }

    /**
     * Check if any of the active objects have changes for the next frame.
     */
    public boolean activeObjectsHaveChanges()
    {
        boolean ret = false;

        synchronized (activeObjects) {
            // Can't short circuit this.  Some objects use the hasUpdate as a pre-render
            for (ActiveObject activeObject : activeObjects)
                if (activeObject.hasChanges())
                    ret = true;
        }

        return ret;
    }

    public boolean surfaceChanged(int width,int height)
    {
        frameSize.setValue(width, height);
        return resize(width,height);
    }

    public void doRender()
    {
        if (view != null)
            view.animate();

        // Run anyone who wants updates
        synchronized (activeObjects) {
            for (ActiveObject activeObject : activeObjects)
                activeObject.activeUpdate();
        }

        render();
    }

    public EGLDisplay display = null;
    public EGLConfig config = null;
    public EGLContext context = null;
    public void setConfig(RenderController otherControl,EGLConfig inConfig)
    {
        EGL10 egl = (EGL10) EGLContext.getEGL();

        if (otherControl == null) {
            display = egl.eglGetCurrentDisplay();
            context = egl.eglGetCurrentContext();
        } else {
            display = otherControl.display;
            context = otherControl.context;
            inConfig = otherControl.config;
        }

        // If we didn't pass in one, we're in offline mode and need to make one
        if (inConfig == null) {
            // current display produces EGL_BAD_DISPLAY (depending on the thread context?)
            display = egl.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

            final int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    //EGL14.EGL_DEPTH_SIZE, 16, // we don't need a depth buffer for offline mode
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT | EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };

            final EGLConfig[] configs = new EGLConfig[1];
            final int[] numConfigs = new int[] { 0 };
            if (!egl.eglChooseConfig(display,attribList,configs, configs.length, numConfigs)) {
                // "If [EGL_OPENGL_ES3_BIT_KHR] is passed into eglChooseConfig and the implementation
                //  supports only an older version of the extension, an EGL_BAD_ATTRIBUTE error should
                //  be generated. Since no matching configs will be found, a robustly-written application
                //  will fail (or fall back to an ES 2.0 rendering path) at this point.
                attribList[9] = EGL14.EGL_OPENGL_ES2_BIT;
                if (!egl.eglChooseConfig(display,attribList,configs, configs.length, numConfigs)) {
                    Log.e("Maply", "Unable to configure OpenGL ES for offline rendering: " + Integer.toHexString(egl.eglGetError()));
                }
                else {
                    //todo: flag that we only have ES2 support somewhere
                    config = configs[0];
                }
            } else {
                config = configs[0];
            }
        } else {
            config = inConfig;
        }

        if (display == null || context == null || config == null) {
            Log.w("Maply", "RenderController::setConfig failed to set" +
                            ((display == null) ? " display" : "") +
                            ((context == null) ? " context" : "") +
                            ((config == null) ? " config" : ""));
        }
    }

    // Managers are thread safe objects for handling adding and removing types of data
    protected VectorManager vecManager;
    protected LoftedPolyManager loftManager;
    protected WideVectorManager wideVecManager;
    protected MarkerManager markerManager;
    protected StickerManager stickerManager;
    protected LabelManager labelManager;
    protected SelectionManager selectionManager;
    protected ComponentManager componentManager;
    protected LayoutManager layoutManager;
    protected ParticleSystemManager particleSystemManager;
    protected LayoutLayer layoutLayer = null;
    protected ShapeManager shapeManager = null;
    protected BillboardManager billboardManager = null;
    protected GeometryManager geomManager = null;

    // Manage bitmaps and their conversion to textures
    TextureManager texManager = new TextureManager();

    public synchronized void shutdown()
    {
        // signal to end any outstanding async tasks
        running = false;

        // Kill the shaders here because they don't do well being finalized
        for (Shader shader : shaders) {
            shader.dispose();
        }
        shaders.clear();

        if (vecManager != null)
            vecManager.dispose();
        if (loftManager != null)
            loftManager.dispose();
        if (wideVecManager != null)
            wideVecManager.dispose();
        if (stickerManager != null)
            stickerManager.dispose();
        if (selectionManager != null)
            selectionManager.dispose();
        if (componentManager != null)
            componentManager.dispose();
        if (labelManager != null)
            labelManager.dispose();
        if (layoutManager != null)
            layoutManager.dispose();
        if (particleSystemManager != null)
            particleSystemManager.dispose();

        vecManager = null;
        loftManager = null;
        wideVecManager = null;
        markerManager = null;
        stickerManager = null;
        labelManager = null;
        selectionManager = null;
        componentManager = null;
        layoutManager = null;
        particleSystemManager = null;
        layoutLayer = null;
        shapeManager = null;
        billboardManager = null;

        texManager = null;

        if (scene != null) {
            // Tear down the scene with GL context, if we're standalone.
            // If we're being used by a BaseController, the addTask won't run because
            // we're already shutting down, but it will have already cleaned up the scene.
            taskMan.addTask(() -> {
                scene.teardownGL();
                scene = null;
            }, ThreadMode.ThreadCurrent);
        }

        if (offlineGLContext != null) {
            EGL10 egl = (EGL10) EGLContext.getEGL();
            if (offlineGLContext.eglDrawSurface != null) {
                Log.d("Maply", "RenderController destroying surface " + offlineGLContext.eglDrawSurface.hashCode());
                egl.eglDestroySurface(display, offlineGLContext.eglDrawSurface);
            }
            if (offlineGLContext.eglContext != null) {
                Log.d("Maply", "RenderController destroying context " + offlineGLContext.eglContext.hashCode());
                egl.eglDestroyContext(display, offlineGLContext.eglContext);
            }
            offlineGLContext = null;
        }

        if (scene != null) {
            scene.teardownGL();
        }

        teardownNative();
    }

    /** RenderControllerInterface **/

    private ArrayList<Light> lights = new ArrayList<>();

    /**
     * Add the given light to the list of active lights.
     * <br>
     * This method will add the given light to our active lights.  Most shaders will recognize these lights and do the calculations.  If you have a custom shader in place, it may or may not use these.
     * Triangle shaders use the lights, but line shaders do not.
     * @param light Light to add.
     */
    public void addLight(final Light light) {
        if (this.lights == null)
            this.lights = new ArrayList<>();
        lights.add(light);
        this.updateLights();
    }

    /**
     * Remove the given light (assuming it's active) from the list of lights.
     * @param light Light to remove.
     */
    public void removeLight(final Light light) {
        if (this.lights == null)
            return;
        this.lights.remove(light);
        this.updateLights();
    }

    // Lights have to be rebuilt every time they change
    private void updateLights() {
        List<DirectionalLight> theLights = new ArrayList<>(lights.size());
        for (Light light : lights) {
            DirectionalLight theLight = new DirectionalLight();
            theLight.setPos(light.getPos());
            theLight.setAmbient(new Point4d(light.getAmbient()[0], light.getAmbient()[1], light.getAmbient()[2], light.getAmbient()[3]));
            theLight.setDiffuse(new Point4d(light.getDiffuse()[0], light.getDiffuse()[1], light.getDiffuse()[2], light.getDiffuse()[3]));
            theLight.setViewDependent(light.isViewDependent());
            theLights.add(theLight);
        }
        replaceLights(theLights.toArray(new DirectionalLight[0]));

        // Clean up lights
        for (DirectionalLight light : theLights) {
            light.dispose();
        }
    }

    /**
     * Clear all the currently active lights.
     * <br>
     * There are a default set of lights, so you'll want to do this before adding your own.
     */
    public void clearLights() {
        this.lights = new ArrayList<>();
        this.updateLights();
    }

    /**
     * Reset the lighting back to its default state at startup.
     * <br>
     * This clears out all the lights and adds in the default starting light source.
     */
    public void resetLights() {
        this.clearLights();

        Light light = new Light();
        light.setPos(new Point3d(0.75, 0.5, -1.0));
        light.setAmbient(0.6f, 0.6f, 0.6f, 1.0f);
        light.setDiffuse(0.5f, 0.5f, 0.5f, 1.0f);
        light.setViewDependent(false);
        this.addLight(light);
    }

    /**
     * Add screen markers to the visual display.  Screen markers are 2D markers that sit
     * on top of the screen display, rather than interacting with the geometry.  Their
     * visual look is defined by the MarkerInfo class.
     *
     * @param markers The markers to add to the display
     * @param markerInfo How the markers should look.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the screen markers for later modification or deletion.
     */
    public ComponentObject addScreenMarkers(final Collection<ScreenMarker> markers,final MarkerInfo markerInfo,ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            int priority = markerInfo.getDrawPriority();
            if (priority <= 0) {
                priority = LabelDrawPriorityDefault;
            }
            markerInfo.setDrawPriority(priority + screenObjectDrawPriorityOffset);

            // Convert to the internal representation of the engine
            ArrayList<InternalMarker> intMarkers = new ArrayList<>(markers.size());
            for (ScreenMarker marker : markers)
            {
                if (!running) {
                    return;
                }
                if (marker.loc == null)
                {
                    Log.d("Maply","Missing location for marker.  Skipping.");
                    continue;
                }

                InternalMarker intMarker = new InternalMarker(marker);
                if (marker.image != null) {
                    long texID = texManager.addTexture(marker.image, scene, changes);
                    if (texID != EmptyIdentity) {
                        intMarker.addTexID(texID);
                    }
                } else if (marker.tex != null) {
                    long texID = marker.tex.texID;
                    if (texID != EmptyIdentity) {
                        intMarker.addTexID(texID);
                    }
                } else if (marker.images != null) {
                    for (MaplyTexture tex : marker.images) {
                        intMarker.addTexID(tex.texID);
                    }
                }
                if (marker.vertexAttributes != null) {
                    intMarker.setVertexAttributes(marker.vertexAttributes.toArray());
                }

                intMarkers.add(intMarker);

                // Keep track of this one for selection
                if (marker.selectable)
                {
                    componentManager.addSelectableObject(marker.ident,marker,compObj);
                }
            }

            // Add the markers and flush the changes
            long markerId = markerManager.addScreenMarkers(intMarkers.toArray(new InternalMarker[0]), markerInfo, changes);

            if (markerId != EmptyIdentity)
            {
                compObj.addMarkerID(markerId);
            }

            for (InternalMarker marker : intMarkers) {
                marker.dispose();
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add moving screen markers to the visual display.  These are the same as the regular
     * screen markers, but they have a start and end point and a duration.
     */
    public ComponentObject addScreenMovingMarkers(final Collection<ScreenMovingMarker> markers,
                                                  final MarkerInfo markerInfo,
                                                  RenderController.ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();
        final double now = System.currentTimeMillis() / 1000.0;

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            int priority = markerInfo.getDrawPriority();
            if (priority <= 0) {
                priority = LabelDrawPriorityDefault;
            }
            markerInfo.setDrawPriority(priority + screenObjectDrawPriorityOffset);

            // Convert to the internal representation of the engine
            ArrayList<InternalMarker> intMarkers = new ArrayList<>(markers.size());
            for (ScreenMovingMarker marker : markers)
            {
                if (!running) {
                    return;
                }

                if (marker.loc == null)
                {
                    Log.d("Maply","Missing location for marker.  Skipping.");
                    continue;
                }

                InternalMarker intMarker = new InternalMarker(marker,now);
                if (marker.duration > 0) {
                    intMarker.setAnimationRange(now,now+marker.duration);
                }
                if (marker.image != null) {
                    long texID = texManager.addTexture(marker.image, scene, changes);
                    if (texID != EmptyIdentity)
                        intMarker.addTexID(texID);
                } else if (marker.tex != null) {
                    long texID = marker.tex.texID;
                    intMarker.addTexID(texID);
                } else if (marker.images != null)
                {
                    for (MaplyTexture tex : marker.images) {
                        intMarker.addTexID(tex.texID);
                    }
                }
                if (marker.vertexAttributes != null)
                    intMarker.setVertexAttributes(marker.vertexAttributes.toArray());

                intMarkers.add(intMarker);

                // Keep track of this one for selection
                if (marker.selectable)
                {
                    componentManager.addSelectableObject(marker.ident,marker,compObj);
                }
            }

            // Add the markers and flush the changes
            long markerId = markerManager.addScreenMarkers(intMarkers.toArray(new InternalMarker[0]), markerInfo, changes);

            if (markerId != EmptyIdentity)
            {
                compObj.addMarkerID(markerId);
            }

            for (InternalMarker marker : intMarkers) {
                marker.dispose();
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add screen markers to the visual display.  Screen markers are 2D markers that sit
     * on top of the screen display, rather than interacting with the geometry.  Their
     * visual look is defined by the MarkerInfo class.
     *
     * @param markers The markers to add to the display
     * @param markerInfo How the markers should look.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the screen markers for later modification or deletion.
     */
    public ComponentObject addMarkers(final Collection<Marker> markers,final MarkerInfo markerInfo,ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            if (markerInfo.getDrawPriority() <= 0) {
                markerInfo.setDrawPriority(MarkerDrawPriorityDefault);
            }

            // Convert to the internal representation of the engine
            ArrayList<InternalMarker> intMarkers = new ArrayList<>(markers.size());
            for (Marker marker : markers)
            {
                if (!running) {
                    return;
                }

                if (marker.loc == null)
                {
                    Log.d("Maply","Missing location for marker.  Skipping.");
                    continue;
                }

                InternalMarker intMarker = new InternalMarker(marker);
                // Map the bitmap to a texture ID
                if (marker.image != null) {
                    long texID = texManager.addTexture(marker.image, scene, changes);
                    if (texID != EmptyIdentity)
                        intMarker.addTexID(texID);
                } else if (marker.images != null) {
                    for (MaplyTexture tex : marker.images) {
                        intMarker.addTexID(tex.texID);
                    }
                } else if (marker.tex != null) {
                    intMarker.addTexID(marker.tex.texID);
                }

                intMarkers.add(intMarker);

                // Keep track of this one for selection
                if (marker.selectable)
                {
                    componentManager.addSelectableObject(marker.ident,marker,compObj);
                }
            }

            // Add the markers and flush the changes
            long markerId = markerManager.addMarkers(intMarkers.toArray(new InternalMarker[0]), markerInfo, changes);

            if (markerId != EmptyIdentity)
            {
                compObj.addMarkerID(markerId);
            }

            for (InternalMarker marker : intMarkers) {
                marker.dispose();
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add screen labels to the display.  Screen labels are 2D labels that float above the 3D geometry
     * and stay fixed in size no matter how the user zoom in or out.  Their visual appearance is controlled
     * by the LabelInfo class.
     *
     * @param labels Labels to add to the display.
     * @param labelInfo The visual appearance of the labels.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the labels for modification or deletion.
     */
    public ComponentObject addScreenLabels(final Collection<ScreenLabel> labels,final LabelInfo labelInfo,ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();
        final LabelManager labelManager = this.labelManager;

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            if (labelInfo.getDrawPriority() <= 0) {
                labelInfo.setDrawPriority(LabelDrawPriorityDefault);
            }

            // Convert to the internal representation for the engine
            ArrayList<InternalLabel> intLabels = new ArrayList<>(labels.size());
            for (ScreenLabel label : labels)
            {
                if (!running) {
                    return;
                }

                if (label.text != null && label.text.length() > 0) {
                    InternalLabel intLabel = new InternalLabel(label,labelInfo);
                    intLabels.add(intLabel);

                    // Keep track of this one for selection
                    if (label.selectable) {
                        componentManager.addSelectableObject(label.ident, label, compObj);
                    }
                }
            }

            long labelId;
            // Note: We can't run multiple of these at once.  The problem is that
            //  we need to pass the JNIEnv deep inside the toolkit and we're setting
            //  on JNIEnv at a time for the CharRenderer callback.
            InternalLabel[] intLabelArr = intLabels.toArray(new InternalLabel[0]);
            synchronized (labelManager) {
                labelId = labelManager.addLabels(intLabelArr, labelInfo, changes);
            }
            if (labelId != EmptyIdentity) {
                compObj.addLabelID(labelId);
            }

            for (InternalLabel label : intLabels) {
                label.dispose();
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add screen labels to the display.  Screen labels are 2D labels that float above the 3D geometry
     * and stay fixed in size no matter how the user zoom in or out.  Their visual appearance is controlled
     * by the LabelInfo class.
     *
     * @param labels Labels to add to the display.
     * @param labelInfo The visual appearance of the labels.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the labels for modification or deletion.
     */
    public ComponentObject addScreenMovingLabels(final Collection<ScreenMovingLabel> labels,
                                                 final LabelInfo labelInfo,ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();
        final double now = System.currentTimeMillis() / 1000.0;
        final LabelManager labelManager = this.labelManager;

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            if (labelInfo.getDrawPriority() <= 0) {
                labelInfo.setDrawPriority(LabelDrawPriorityDefault);
            }

            // Convert to the internal representation for the engine
            ArrayList<InternalLabel> intLabels = new ArrayList<>(labels.size());
            for (ScreenMovingLabel label : labels) {
                if (!running) {
                    return;
                }

                if (label.text != null && label.text.length() > 0) {
                    InternalLabel intLabel = new InternalLabel(label,labelInfo,now);
                    intLabels.add(intLabel);

                    // Keep track of this one for selection
                    if (label.selectable) {
                        componentManager.addSelectableObject(label.ident, label, compObj);
                    }
                }
            }

            long labelId;
            // Note: We can't run multiple of these at once.  The problem is that
            //  we need to pass the JNIEnv deep inside the toolkit and we're setting
            //  on JNIEnv at a time for the CharRenderer callback.
            InternalLabel[] intLabelArr = intLabels.toArray(new InternalLabel[0]);
            synchronized (labelManager) {
                labelId = labelManager.addLabels(intLabelArr, labelInfo, changes);
            }
            if (labelId != EmptyIdentity) {
                compObj.addLabelID(labelId);
            }

            for (InternalLabel label : intLabels) {
                label.dispose();
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add vectors to the MaplyController to display.  Vectors are linear or areal
     * features with line width, filled style, color and so forth defined by the
     * VectorInfo class.
     *
     * @param vecs A list of VectorObject's created by the user or read in from various sources.
     * @param vecInfo A description of how the vectors should look.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return The ComponentObject representing the vectors.  This is necessary for modifying
     * or deleting the vectors once created.
     */
    public ComponentObject addVectors(final Collection<VectorObject> vecs,final VectorInfo vecInfo,
                                      RenderController.ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (vecInfo.getDrawPriority() <= 0) {
                vecInfo.setDrawPriority(VectorDrawPriorityDefault);
            }

            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();
            long vecId = vecManager.addVectors(vecs.toArray(new VectorObject[0]), vecInfo, changes);

            // Track the vector ID for later use
            if (vecId != EmptyIdentity)
                compObj.addVectorID(vecId);

            // Keep track of this one for selection
            for (VectorObject vecObj : vecs)
            {
                if (!running) {
                    return;
                }

                if (vecObj.getSelectable()) {
                    compObj.addVector(vecObj);
                    componentManager.addSelectableObject(vecObj.ident, vecObj, compObj);
                }
            }

            if (vecInfo.disposeAfterUse || disposeAfterRemoval) {
                for (VectorObject vecObj : vecs) {
                    if (!vecObj.getSelectable()) {
                        vecObj.dispose();
                    }
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add Lofted Polygons to the MaplyController to display.
     * <br>
     * Lofted polygons require areal features as outlines.  The result will be
     * a tent-like visual with optional sides and a top.
     *
     * @param vecs A list of VectorObject's created by the user or read in from various sources.
     * @param loftInfo A description of how the lofted polygons should look.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return The ComponentObject representing the vectors.  This is necessary for modifying
     * or deleting the features once created.
     */
    public ComponentObject addLoftedPolys(final Collection<VectorObject> vecs, final LoftedPolyInfo loftInfo, final ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (loftInfo.getDrawPriority() <= 0) {
                loftInfo.setDrawPriority(LoftedPolysDrawPriorityDefault);
            }

            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();
            long loftID = loftManager.addPolys(vecs.toArray(new VectorObject[0]), loftInfo, changes);

            // Track the vector ID for later use
            if (loftID != EmptyIdentity)
                compObj.addLoftID(loftID);

            // TODO: Porting
            //for (VectorObject vecObj : vecs)
            //{
            //    // Keep track of this one for selection
            //    if (vecObj.getSelectable())
            //        compObj.addSelectID(vecObj.getID());
            //}

            if (loftInfo.disposeAfterUse || disposeAfterRemoval) {
                for (VectorObject vecObj : vecs) {
                    if (!vecObj.getSelectable()) {
                        vecObj.dispose();
                    }
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Change the visual representation of the given vectors.
     * @param vecObj The component object returned by the original addVectors() call.
     * @param vecInfo Visual representation to use for the changes.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     */
    public void changeVector(final ComponentObject vecObj,final VectorInfo vecInfo,ThreadMode mode)
    {
        if (vecObj == null)
            return;

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();
            long[] vecIDs = vecObj.getVectorIDs();
            if (vecIDs != null) {
                vecManager.changeVectors(vecIDs, vecInfo, changes);
                processChangeSet(changes);
            }
        }, mode);
    }

    /**
     * Change the visual representation of the given vectors.
     * @param vecObj The component object returned by the original addVectors() call.
     * @param vecInfo Visual representation to use for the changes.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     */
    public void changeWideVector(final ComponentObject vecObj,final WideVectorInfo vecInfo,ThreadMode mode)
    {
        if (vecObj == null)
            return;

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();
            long[] vecIDs = vecObj.getVectorIDs();
            if (vecIDs != null) {
                // todo: implement this
                //vecManager.changeWideVectors(vecIDs, vecInfo, changes);
                processChangeSet(changes);
            }
        }, mode);
    }

    /**
     * Instance an existing set of vectors and modify various parameters for reuse.
     * This is useful if you want to overlay the same vectors twice with different widths,
     * for example.
     */
    public ComponentObject instanceVectors(final ComponentObject vecObj, final VectorInfo vecInfo, ThreadMode mode)
    {
        if (vecObj == null)
            return null;

        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (vecInfo.getDrawPriority() <= 0) {
                vecInfo.setDrawPriority(VectorDrawPriorityDefault);
            }

            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();
            long[] vecIDs = vecObj.getVectorIDs();
            if (vecIDs != null) {
                for (long vecID : vecIDs) {
                    long newID = vecManager.instanceVectors(vecID, vecInfo, changes);
                    if (newID != EmptyIdentity)
                        compObj.addVectorID(newID);
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add wide vectors to the MaplyController to display.  Vectors are linear or areal
     * features with line width, filled style, color and so forth defined by the
     * WideVectorInfo class.
     * <br>
     * Wide vectors differ from regular lines in that they're implemented with a more
     * complicated shader.  They can be arbitrarily large, have textures, and have a transparent
     * falloff at the edges.  This makes them look anti-aliased.
     *
     * @param vecs A list of VectorObject's created by the user or read in from various sources.
     * @param wideVecInfo A description of how the vectors should look.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return The ComponentObject representing the vectors.  This is necessary for modifying
     * or deleting the vectors once created.
     */
    public ComponentObject addWideVectors(final Collection<VectorObject> vecs,
                                          final WideVectorInfo wideVecInfo,
                                          ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (wideVecInfo.getDrawPriority() <= 0) {
                wideVecInfo.setDrawPriority(VectorDrawPriorityDefault);
            }

            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();
            long vecId = wideVecManager.addVectors(vecs.toArray(new VectorObject[0]), wideVecInfo, changes);

            // Track the vector ID for later use
            if (vecId != EmptyIdentity) {
                compObj.addWideVectorID(vecId);
            }

            // todo: should we still produce a component object and add it as selectable if addVectors returned zero?

            for (VectorObject vecObj : vecs) {
                if (vecObj.getSelectable()) {
                    // Keep track of this one for selection
                    compObj.addVector(vecObj);
                    componentManager.addSelectableObject(vecObj.ident,vecObj,compObj);
                } else if (wideVecInfo.disposeAfterUse || disposeAfterRemoval) {
                    // Discard it
                    vecObj.dispose();
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Instance an existing set of wide vectors but change their parameters.
     * <br>
     * Wide vectors can take up a lot of memory.  So if you want to display the same set with
     * different parameters (e.g. width, color) this is the way to do it.
     *
     * @param inCompObj The Component Object returned by an addWideVectors call.
     * @param wideVecInfo How we want the vectors to look.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return The ComponentObject representing the instanced wide vectors.  This is necessary for modifying
     * or deleting the instance once created.
     */
    public ComponentObject instanceWideVectors(final ComponentObject inCompObj,
                                               final WideVectorInfo wideVecInfo,
                                               ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (wideVecInfo.getDrawPriority() <= 0) {
                wideVecInfo.setDrawPriority(VectorDrawPriorityDefault);
            }

            // Vectors are simple enough to just add
            ChangeSet changes = new ChangeSet();

            for (long vecID : inCompObj.getWideVectorIDs()) {
                if (!running) {
                    return;
                }

                long instID = wideVecManager.instanceVectors(vecID,wideVecInfo,changes);

                if (instID != EmptyIdentity)
                    compObj.addWideVectorID(instID);
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    // TODO: Fill this in
//    public ComponentObject addModelInstances();

    // TODO: Fill this in
//    public ComponentObject addGeometry();

    /**
     * This method will add the given MaplyShape derived objects to the current scene.
     * It will use the parameters in the description dictionary and it will do it on the thread specified.
     * @param shapes An array of Shape derived objects
     * @param shapeInfo Info controlling how the shapes look
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     */
    public ComponentObject addShapes(final Collection<Shape> shapes, final ShapeInfo shapeInfo, ThreadMode mode) {
        final ComponentObject compObj = componentManager.makeComponentObject();
        final ChangeSet changes = new ChangeSet();

        taskMan.addTask(() -> {
            long shapeId = shapeManager.addShapes(shapes.toArray(new Shape[0]), shapeInfo, changes);
            if (shapeId != EmptyIdentity)
                compObj.addShapeID(shapeId);

            for (Shape shape : shapes) {
                if (shape.isSelectable()) {
                    if (!running) {
                        return;
                    }

                    componentManager.addSelectableObject(shape.getSelectID(), shape, compObj);
                }
            }

            if (shapeInfo.disposeAfterUse || disposeAfterRemoval) {
                for (Shape shape : shapes) {
                    shape.dispose();
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add stickers on top of the globe or map.  Stickers are 2D objects that drape over a defined
     * area.
     *
     * @param stickers The list of stickers to apply.
     * @param stickerInfo Parameters that cover all the stickers in question.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the stickers for later modification or deletion.
     */
    public ComponentObject addStickers(final Collection<Sticker> stickers,final StickerInfo stickerInfo,ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            if (stickerInfo.getDrawPriority() <= 0) {
                stickerInfo.setDrawPriority(StickerDrawPriorityDefault);
            }

            // Stickers are added one at a time for some reason
            long stickerID = stickerManager.addStickers(stickers.toArray(new Sticker[0]), stickerInfo, changes);

            if (stickerID != EmptyIdentity) {
                compObj.addStickerID(stickerID);
            }

            if (stickerInfo.disposeAfterUse || disposeAfterRemoval) {
                for (Sticker sticker : stickers) {
                    sticker.dispose();
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Change the visual representation for the given sticker.
     *
     * @param stickerObj The sticker to change.
     * @param stickerInfo Parameters to change.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the stickers for later modification or deletion.
     */
    public ComponentObject changeSticker(final ComponentObject stickerObj,
                                         final StickerInfo stickerInfo,
                                         ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            long[] stickerIDs = stickerObj.getStickerIDs();
            if (stickerIDs != null && stickerIDs.length > 0) {
                for (long stickerID : stickerIDs) {
                    if (!running) {
                        return;
                    }
                    stickerManager.changeSticker(stickerID, stickerInfo, changes);
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Billboards are rectangles pointed toward the viewer.  They can either be upright, tied to a
     * surface, or oriented completely toward the user.
     */
    public ComponentObject addBillboards(final Collection<Billboard> bills,
                                         final BillboardInfo info,
                                         final RenderController.ThreadMode threadMode) {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (info.getDrawPriority() <= 0) {
                info.setDrawPriority(BillboardDrawPriorityDefault);
            }

            ChangeSet changes = new ChangeSet();

            // Have to set the shader ID if it's not already
            if (info.getShaderID() == 0) {
                // TODO: Share these constants with the c++ code
                String shaderName = (info.getOrient() == BillboardInfo.Orient.Eye) ? Shader.BillboardEyeShader : Shader.BillboardGroundShader;
                Shader shader = getShader(shaderName);
                if (shader != null) {
                    info.setShaderID(shader.getID());
                } else {
                    Log.w("Maply", "Billboard shader not found");
                }
            }

            for (Billboard bill : bills) {
                if (!running) {
                    return;
                }
                // Convert to display space
                Point3d center = bill.getCenter();
                Point3d localPt =coordAdapter.getCoordSystem().geographicToLocal(new Point3d(center.getX(),center.getY(),0.0));
                Point3d dispTmp =coordAdapter.localToDisplay(localPt);
                Point3d dispPt = dispTmp.multiplyBy(center.getZ()/6371000.0+1.0);
                bill.setCenter(dispPt);

                if (bill.getSelectable()) {
                    bill.setSelectID(Identifiable.genID());
                    componentManager.addSelectableObject(bill.getSelectID(), bill, compObj);
                }

                // Turn any screen objects into billboard polygons
                bill.flatten();
            }

            long billId = billboardManager.addBillboards(bills.toArray(new Billboard[0]), info, changes);
            compObj.addBillboardID(billId);

            if (info.disposeAfterUse || disposeAfterRemoval) {
                for (Billboard bill : bills) {
                    if (!bill.getSelectable()) {
                        bill.dispose();
                    }
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, threadMode);

        return compObj;
    }

    /**
     * Add the geometry points.  These are raw points that get fed to a shader.

     * @param ptList The points to add.
     * @param geomInfo Parameters to set things up with.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     * @return This represents the geometry points for later modification or deletion.
     */
    public ComponentObject addPoints(final Collection<Points> ptList,
                                     final GeometryInfo geomInfo,
                                     RenderController.ThreadMode mode)
    {
        final ComponentObject compObj = componentManager.makeComponentObject();

        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            if (geomInfo.getDrawPriority() <= 0) {
                geomInfo.setDrawPriority(ParticleSystemDrawPriorityDefault);
            }

            ChangeSet changes = new ChangeSet();

            // Stickers are added one at a time for some reason
            for (Points pts: ptList) {
                if (!running) {
                    return;
                }
                //Matrix4d mat = pts.mat != null ? pts.mat : new Matrix4d();
                long geomID = geomManager.addGeometryPoints(pts.rawPoints,pts.mat,geomInfo,changes);

                if (geomID != EmptyIdentity) {
                    compObj.addGeometryID(geomID);
                }
            }

            if (geomInfo.disposeAfterUse || disposeAfterRemoval) {
                for (Points pts : ptList) {
                    pts.rawPoints.dispose();
                }
            }

            componentManager.addComponentObject(compObj, changes);
            processChangeSet(changes);
        }, mode);

        return compObj;
    }

    /**
     * Add texture to the system with the given settings.
     * @param image Image to add.
     * @param settings Settings to use.
     * @param mode Add on the current thread or elsewhere.
     */
    public MaplyTexture addTexture(final Bitmap image,
                                   final RenderController.TextureSettings settings,
                                   RenderController.ThreadMode mode)
    {
        final MaplyTexture texture = new MaplyTexture();
        final Texture rawTex = new Texture();
        texture.texID = rawTex.getID();

        // Possibly do the work somewhere else
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            rawTex.setBitmap(image,settings.imageFormat.ordinal());
            rawTex.setSettings(settings.wrapU,settings.wrapV);
            changes.addTexture(rawTex, scene, settings.filterType.ordinal());
            processChangeSet(changes);
        }, mode);

        return texture;
    }

    /**
     * Add texture to the system with the given settings.
     * @param rawTex Texture to add.
     * @param settings Settings to use.
     * @param mode Add on the current thread or elsewhere.
     */
    public MaplyTexture addTexture(final Texture rawTex,
                                   final TextureSettings settings,
                                   ThreadMode mode)
    {
        final MaplyTexture texture = new MaplyTexture();

        // Possibly do the work somewhere else
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();
            texture.texID = rawTex.getID();
            changes.addTexture(rawTex, scene, settings.filterType.ordinal());
            processChangeSet(changes);
        }, mode);

        return texture;
    }

    /**
     * Create an empty texture of the given size.
     * @param width Width of the resulting texture
     * @param height Height of the resulting texture
     * @param settings Other texture related settings
     * @param mode Which thread to do the work on
     * @return The new texture (or a reference to it, anyway)
     */
    public MaplyTexture createTexture(final int width,
                                      final int height,
                                      final TextureSettings settings,
                                      ThreadMode mode)
    {
        final MaplyTexture texture = new MaplyTexture();
        final Texture rawTex = new Texture();
        texture.texID = rawTex.getID();
        texture.width = width;
        texture.height = height;

        // Possibly do the work somewhere else
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            rawTex.setSize(width,height);
            rawTex.setIsEmpty(true);
            changes.addTexture(rawTex, scene, settings.filterType.ordinal());
            processChangeSet(changes);
        }, mode);

        return texture;
    }

    /**
     * Remove a texture from the scene.
     * @param texs Textures to remove.
     * @param mode Remove immediately (current thread) or elsewhere.
     */
    public void removeTextures(final Collection<MaplyTexture> texs,ThreadMode mode)
    {
        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            for (MaplyTexture tex : texs) {
                changes.removeTexture(tex.texID);
            }
            processChangeSet(changes);
        }, mode);
    }

    public void removeTexture(final MaplyTexture tex,ThreadMode mode)
    {
        ArrayList<MaplyTexture> texs = new ArrayList<>(1);
        texs.add(tex);

        removeTextures(texs,mode);
    }

    /**
     * This version of removeTexture takes texture IDs.  Thus you don't
     * have to keep the MaplyTexture around.
     *
     * @param texIDs Textures to remove
     * @param mode Remove immediately (current thread) or elsewhere.
     */
    public void removeTexturesByID(final Collection<Long> texIDs,ThreadMode mode)
    {
        // Do the actual work on the layer thread
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();

            for (Long texID : texIDs) {
                changes.removeTexture(texID);
            }

            processChangeSet(changes);
        }, mode);
    }

    /** Add a render target to the system
     * <br>
     * Sets up a render target and will start rendering to it on the next frame.
     * Keep the render target around so you can remove it later.
     */
    public void addRenderTarget(RenderTarget renderTarget)
    {
        scene.addRenderTargetNative(renderTarget.renderTargetID,
                renderTarget.texture.width,renderTarget.texture.height,
                renderTarget.texture.texID,
                renderTarget.clearEveryFrame,
                renderTarget.clearVal,
                renderTarget.blend,
                Color.red(renderTarget.color)/255.f,Color.green(renderTarget.color)/255.f,Color.blue(renderTarget.color)/255.f,Color.alpha(renderTarget.color)/255.f);
    }

    /**
     * Point the render target at a different texture.
     */
    public void changeRenderTarget(RenderTarget renderTarget, MaplyTexture tex)
    {
       scene.changeRenderTarget(renderTarget.renderTargetID,tex.texID);
    }

    /** Remove the given render target from the system.
     * <br>
     * Ask the system to stop drawing to the given render target.  It will do this on the next frame.
     */
    public void removeRenderTarget(RenderTarget renderTarget)
    {
        scene.removeRenderTargetNative(renderTarget.renderTargetID);
    }

    /**
     * Ask the render target to clear itself.
     */
    public void clearRenderTarget(final RenderTarget renderTarget, ThreadMode mode)
    {
        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();
            changes.clearRenderTarget(renderTarget.renderTargetID);
            processChangeSet(changes);
        }, mode);
    }

    /**
     * Disable the given objects. These were the objects returned by the various
     * add calls.  Once called, the objects will be invisible, but can be made
     * visible once again with enableObjects()
     *
     * @param compObjs Objects to disable in the display.
     * @param mode Where to execute the add.  Choose ThreadAny by default.
     */
    public void disableObjects(final Collection<ComponentObject> compObjs,ThreadMode mode)
    {
        if (compObjs == null || compObjs.size() == 0)
            return;

        final ComponentObject[] localCompObjs = compObjs.toArray(new ComponentObject[0]);

        taskMan.addTask(() -> {
            ChangeSet changes = new ChangeSet();
            for (ComponentObject compObj : localCompObjs) {
                if (compObj != null) {
                    componentManager.enableComponentObject(compObj, false, changes);
                }
            }
            processChangeSet(changes);
        }, mode);
    }

    /**
     * Enable the display for the given objects.  These objects were returned
     * by the various add calls.  To disable the display, call disableObjects().
     *
     * @param compObjs Objects to enable disable.
     * @param mode Where to execute the enable.  Choose ThreadAny by default.
     */
    public void enableObjects(final Collection<ComponentObject> compObjs,ThreadMode mode)
    {
        if (compObjs == null)
            return;

        final ComponentObject[] localCompObjs = compObjs.toArray(new ComponentObject[0]);
        enableObjects(localCompObjs,mode);
    }

    /**
     * Enable the display for the given objects.  These objects were returned
     * by the various add calls.  To disable the display, call disableObjects().
     *
     * @param compObjs Objects to enable disable.
     * @param mode Where to execute the enable.  Choose ThreadAny by default.
     */
    public void enableObjects(final ComponentObject[] compObjs,ThreadMode mode) {
        if (compObjs == null || compObjs.length == 0)
            return;

        taskMan.addTask(() -> {
            final ComponentManager compMan = componentManager;
            if (compMan == null || (layoutLayer != null && layoutLayer.isShuttingDown)) {
                return;
            }
            ChangeSet changes = new ChangeSet();
            for (ComponentObject compObj : compObjs) {
                if (compObj != null) {
                    compMan.enableComponentObject(compObj, true, changes);
                }
            }
            processChangeSet(changes);
        }, mode);
    }

    /**
     * Remove the given component objects from the display.  This will permanently remove them
     * from Maply.  The component objects were returned from the various add calls.
     *
     * @param compObjs Component Objects to remove.
     * @param mode Where to execute the remove.  Choose ThreadAny by default.
     */
    public void removeObjects(final Collection<ComponentObject> compObjs,ThreadMode mode)
    {
        if (compObjs == null)
            return;

        removeObjects(compObjs.toArray(new ComponentObject[0]),mode);
    }

    /**
     * Remove the given component objects from the display.  This will permanently remove them
     * from Maply.  The component objects were returned from the various add calls.
     *
     * @param compObjs Component Objects to remove.
     * @param mode Where to execute the remove.  Choose ThreadAny by default.
     */
    public void removeObjects(final ComponentObject[] compObjs,ThreadMode mode) {
        if (compObjs == null || compObjs.length == 0)
            return;

        taskMan.addTask(() -> {
            final ComponentManager compMan = componentManager;
            if (compMan == null || (layoutLayer != null && layoutLayer.isShuttingDown)) {
                return;
            }

            ChangeSet changes = new ChangeSet();
            componentManager.removeComponentObjects(compObjs,changes,disposeAfterRemoval);
            processChangeSet(changes);
        }, mode);
    }

    /**
     * Remove a simple object from the display.
     *
     * @param compObj Component Object to remove.
     * @param mode Where to execute the remove.  Choose ThreadAny by default.
     */
    public void removeObject(final ComponentObject compObj,ThreadMode mode)
    {
        List<ComponentObject> compObjs = new ArrayList<>(1);
        compObjs.add(compObj);

        removeObjects(compObjs,mode);
    }

    // All the shaders currently in use
    final private ArrayList<Shader> shaders = new ArrayList<>(50);

    /**
     * Associate a shader with the given scene name.  These names let us override existing shaders, as well as adding our own.
     * @param shader The shader to add.
     */
    public void addShaderProgram(final Shader shader)
    {
        synchronized (shaders) {
            shaders.add(shader);
        }
        scene.addShaderProgram(shader);
    }

    /**
     * In the render controller setup, we stand up the full set of default
     * shaders used by the system.  To reflect things on this side, we'll
     * add them to this array as well.
     */
    protected void addPreBuiltShader(Shader shader)
    {
        synchronized (shaders) {
            shaders.add(shader);
        }
        shader.control = new WeakReference<>(this);
    }

    /**
     * Find a shader by name
     * @param name Name of the shader to return
     * @return The shader with the name or null
     */
    public Shader getShader(String name)
    {
        synchronized (shaders) {
            for (Shader shader : shaders) {
                String shaderName = shader.getName();
                if (shaderName.equals(name))
                    return shader;
            }
        }

        return null;
    }

    /**
     * Remove the given shader from active use.
     */
    public void removeShader(Shader shader)
    {
        if (shader != null) {
            synchronized (shaders) {
                if (shaders.contains(shader)) {
                    scene.removeShaderProgram(shader.getID());
                    shaders.remove(shader);
                }
            }
        }
    }

    int clearColor = Color.BLACK;

    /**
     * Set the color for the OpenGL ES background.
     */
    public void setClearColor(int color)
    {
        clearColor = color;

//		if (tempBackground != null)
//			tempBackground.setColor(clearColor);

        setClearColor(Color.red(color)/255.f,Color.green(color)/255.f,Color.blue(color)/255.f,Color.alpha(color)/255.f);
    }

    public double heightForMapScale(double scale)
    {
        return view.heightForMapScale(scale,frameSize.getX(),frameSize.getY());
    }

    public double currentMapZoom(Point2d geoCoord)
    {
        return view.currentMapZoom(frameSize.getX(),frameSize.getY(),geoCoord.getY());
    }

    public double currentMapScale()
    {
        return view.currentMapScale(frameSize.getX(),frameSize.getY());
    }

    /**
     * Returns the framebuffer size as ints.
     */
    public int[] getFrameBufferSize()
    {
        int[] sizes = new int[2];
        sizes[0] = (int)frameSize.getX();
        sizes[1] = (int)frameSize.getY();

        return sizes;
    }

    // A no-op for the standalone renderer
    public void requestRender()
    {
    }

    // Used in standalone mode
    ContextInfo offlineGLContext = null;

    // Used only in standalone mode
    // Returns previous context, or null if the context could not be changed
    public ContextInfo setEGLContext(ContextInfo cInfo) {
        if (cInfo == null) {
            cInfo = offlineGLContext;
        }

        EGL10 egl = (EGL10) EGLContext.getEGL();
        ContextInfo current = getEGLContext();

        Thread t = Thread.currentThread();
        if (cInfo != null)
        {
            if (!egl.eglMakeCurrent(display, cInfo.eglDrawSurface, cInfo.eglReadSurface, cInfo.eglContext)) {
                return null;
            }
        } else {
            egl.eglMakeCurrent(display, egl.EGL_NO_SURFACE, egl.EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }

        return current;
    }

    // Return a description of the current context
    public static ContextInfo getEGLContext() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        return new ContextInfo(
                egl.eglGetCurrentDisplay(),
                egl.eglGetCurrentContext(),
                egl.eglGetCurrentSurface(EGL_DRAW),
                egl.eglGetCurrentSurface(EGL_READ));
    }

    public void processChangeSet(ChangeSet changes)
    {
        if (changes != null) {
            if (scene != null && running) {
                changes.process(this, scene);
            }
            changes.dispose();
        }
    }

    public ContextWrapper wrapTempContext(RenderController.ThreadMode threadMode) {
        return new ContextWrapper(this, setupTempContext(threadMode));
    }

    // Don't need this in standalone mode
    public ContextInfo setupTempContext(ThreadMode threadMode)
    {
        return null;
    }

    // Don't need this in standalone mode
    public void clearTempContext(ContextInfo cInfo)
    {
    }

    /**
     * In offline render mode, clear the context
     * Only do this if you're working in offline mode
     */
    public void clearContext()
    {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        egl.eglMakeCurrent(display, egl.EGL_NO_SURFACE, egl.EGL_NO_SURFACE, egl.EGL_NO_CONTEXT);
    }

    /**
     * Render to and return a Bitmap
     * You should have already set the context at this point
     */
    public Bitmap renderToBitmap() {
        final Bitmap bitmap = Bitmap.createBitmap((int)frameSize.getX(), (int)frameSize.getY(), Bitmap.Config.ARGB_8888);
        renderToBitmapNative(bitmap);
        return bitmap;
    }

    private boolean running = true;

    public boolean isRunning() {
        return running;
    }

    public native void setScene(Scene scene);
    public native void setupShadersNative();
    public native void setViewNative(View view);
    public native void setClearColor(float r,float g,float b,float a);
    private native boolean teardownNative();
    protected native boolean resize(int width,int height);
    protected native void render();
    protected native boolean hasChanges();
    public native void setPerfInterval(int perfInterval);
    public native void addLight(DirectionalLight light);
    public native void replaceLights(DirectionalLight[] lights);
    protected native void renderToBitmapNative(Bitmap outBitmap);

    private int screenObjectDrawPriorityOffset = 1000000;
    public int getScreenObjectDrawPriorityOffset() { return screenObjectDrawPriorityOffset; }
    public void setScreenObjectDrawPriorityOffset(int offset) { screenObjectDrawPriorityOffset = offset; }

    static {
        nativeInit();
    }
    public void finalize() {
        setScene(null);
        dispose();
    }
    private static native void nativeInit();
    native void initialise(int width,int height);
    native void initialise();
    native void dispose();

    @SuppressWarnings({"unused", "RedundantSuppression"})
    private long nativeHandle;

    public void dumpFailureInfo(String failureLocation) {
        Log.e("Maply", "Context failure in local renderer: " + failureLocation);
    }
}