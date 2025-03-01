/*  MaplyClusterGenerator.java
 *  WhirlyGlobeLib
 *
 *  Created by jmnavarro
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

import androidx.annotation.Keep;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;

import static com.mousebird.maply.RenderController.EmptyIdentity;

/**
 * Fill in this protocol to provide images when individual markers/labels are clustered.
 * <p>
 * This is the protocol for marker/label clustering.
 * You must fill this in and register the cluster generator.
 */
public class ClusterGenerator
{
    protected ClusterGenerator(BaseController control) {
        baseController = new WeakReference<>(control);
    }

    /**
     * Called at the start of clustering.
     * <p>
     * Called right before we start generating clusters.  Do you setup here if need be.
     */
    @Keep
    @SuppressWarnings("unused")		// Used from JNI
    public void startClusterGroup() {
        if (oldTextures != null) {
            BaseController control = baseController.get();
            if (control != null) {
                control.removeTexturesByID(new ArrayList<>(oldTextures), RenderController.ThreadMode.ThreadCurrent);
            }
            oldTextures = null;
        }

        oldTextures = currentTextures;
        currentTextures = new HashSet<>();
    }

    /**
     * Generate a cluster group for a given collection of markers.
     * <p>
     * Generate an image and size to represent the number of marker/labels we're consolidating.
     * @param clusterInfo Description of the cluster
     * @return a cluster group for a given collection of markers.
     */
    public ClusterGroup makeClusterGroup(ClusterInfo clusterInfo) {
        return null;
    }

    // The C++ code calls this to get a Bitmap then we call makeClusterGroup
    @Keep
    @SuppressWarnings("unused")		// Used from JNI
    private long makeClusterGroupJNI(int num, String[] uniqueIDs) {
        ClusterInfo clusterInfo = new ClusterInfo(num, uniqueIDs);
        ClusterGroup newGroup = makeClusterGroup(clusterInfo);
        if (newGroup != null) {
            currentTextures.add(newGroup.tex.texID);
            return newGroup.tex.texID;
        }
        return EmptyIdentity;
    }

    /**
     * Called at the end of clustering.
     * <p>
     * If you were doing optimization (for image reuse, say) clean it up here.
     */
    @Keep
    @SuppressWarnings("unused")		// Used from JNI
    public void endClusterGroup() {
    }

    /**
     * Clean up resources on removal
     */
    @Keep
    @SuppressWarnings("unused")		// Used from JNI
    public void shutdown() {
    }

    /**
     * The Cluster number is referenced by screen markers.  We group all the markers that
     * share a cluster number together.
     * @return the cluster number we're covering
     */
    public int clusterNumber() {
        return 0;
    }

    /**
     * The size of the cluster that will be created.
     * <p>
     * This is the biggest cluster you're likely to create.  We use it to figure overlaps between clusters.
     * @return The size of the cluster that will be created.
     */
    public Point2d clusterLayoutSize() {
        return new Point2d(32.0,32.0);
    }

    /**
     * Set this if you want cluster to be user selectable.  On by default.
     * @return true
     */
    public boolean selectable() {
        return true;
    }

    /**
     * How long to animate markers the join and leave a cluster
     * @return time in seconds
     */
    public double markerAnimationTime() {
        return 1.0;
    }

    /**
     * The shader to use for moving objects around
     * <p>
     * If you're doing animation from point to cluster you need to provide a suitable shader.
     * @return null
     */
    public Shader motionShader() {
        return null;
    }

    protected final WeakReference<BaseController> baseController;
    private HashSet<Long> currentTextures,oldTextures;
}
