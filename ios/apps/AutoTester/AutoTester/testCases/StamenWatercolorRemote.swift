//
//  MapboxSatellite.swift
//  AutoTester
//
//  Created by jmnavarro on 13/10/15.
//  Copyright © 2015-2017 mousebird consulting.
//

import UIKit

class StamenWatercolorRemote: MaplyTestCase {

	override init() {
		super.init()

		self.name = "Stamen Watercolor Remote"
		self.implementations = [.globe, .map]
	}
    
    var imageLoader : MaplyQuadImageLoader? = nil
	
	func setupLoader(_ baseVC: MaplyRenderControllerProtocol) -> MaplyQuadImageLoader? {
        let cacheDir = NSSearchPathForDirectoriesInDomains(.cachesDirectory, .userDomainMask, true)[0]
        let thisCacheDir = "\(cacheDir)/stamentiles/"
        let maxZoom = Int32(16)
        let tileInfo = MaplyRemoteTileInfoNew(baseURL: "http://tile.stamen.com/watercolor/{z}/{x}/{y}.png",
                                              minZoom: Int32(0),
                                              maxZoom: Int32(maxZoom))
        tileInfo.cacheDir = thisCacheDir
        
        // Parameters describing how we want a globe broken down
        let sampleParams = MaplySamplingParams()
        sampleParams.coordSys = MaplySphericalMercator(webStandard: ())
        sampleParams.coverPoles = true
        sampleParams.edgeMatching = true
        sampleParams.maxZoom = tileInfo.maxZoom()
        sampleParams.singleLevel = true         // only show one level at a time
        sampleParams.forceMinLevel = true       // Keep level 0 even when zoomed *way* out
        sampleParams.minImportanceTop = 1       // (otherwise the globe disappears past h=~2)
        sampleParams.minImportance = 1024.0 * 1024.0 / 4.0
        
        guard let imageLoader = MaplyQuadImageLoader(params: sampleParams, tileInfo: tileInfo, viewC: baseVC) else {
            return nil
        }
        imageLoader.debugMode = true

        // Store the images as RGB 5/6/5 textures to save memory.  Not supported on iOS simulator.
        // "Don't use the following pixel formats: ... b5g6r5Unorm"
        // https://developer.apple.com/documentation/metal/developing_metal_apps_that_run_in_simulator
#if !targetEnvironment(simulator)
        imageLoader.imageFormat = .imageUShort565
#endif
        
        return imageLoader
	}

	override func setUpWithGlobe(_ globeVC: WhirlyGlobeViewController) {
		imageLoader = setupLoader(globeVC)
        		
		globeVC.keepNorthUp = true
		globeVC.animate(toPosition: MaplyCoordinateMakeWithDegrees(-3.6704803, 40.5023056), time: 1.0)
        
//        globeVC.globeCenter = CGPoint(x: globeVC.view.center.x, y: globeVC.view.center.y + 0.33*globeVC.view.frame.size.height/2.0)
	}

	override func setUpWithMap(_ mapVC: MaplyViewController) {
		imageLoader = setupLoader(mapVC)

        mapVC.rotateGesture = false
		mapVC.animate(toPosition: MaplyCoordinateMakeWithDegrees(-3.6704803, 40.5023056), height: 1.0, time: 1.0)
		mapVC.setZoomLimitsMin(0.01, max: 5.0)
	}
    
    override func stop() {
        imageLoader?.shutdown()
        imageLoader = nil
        
        super.stop()
    }
}
