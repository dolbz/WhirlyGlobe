/*
 *  VectorData.h
 *  WhirlyGlobeLib
 *
 *  Created by Steve Gifford on 3/7/11.
 *  Copyright 2011-2019 mousebird consulting
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
 *
 */

#import <math.h>
#import <vector>
#import <set>
#import <unordered_set>
#import <map>
#import <functional>
#import "Identifiable.h"
#import "WhirlyVector.h"
#import "WhirlyGeometry.h"
#import "CoordSystem.h"
#import "Dictionary.h"

namespace WhirlyKit
{
    
/// The base class for vector shapes.  All shapes
///  have attribute and an MBR.
class VectorShape : public Identifiable
{
public:	
	/// Set the attribute dictionary
	void setAttrDict(MutableDictionaryRef newDict);
	
	/// Return the attr dict
    MutableDictionaryRef getAttrDict() const;
    const MutableDictionaryRef &getAttrDictRef() const;

    /// Return the geoMbr
    virtual GeoMbr calcGeoMbr() = 0;
	
protected:
	VectorShape();
	virtual ~VectorShape();

	MutableDictionaryRef attrDict;
};

class VectorAreal;
class VectorLinear;
class VectorLinear3d;
class VectorPoints;
class VectorTriangles;

/// Reference counted version of the base vector shape
typedef std::shared_ptr<VectorShape> VectorShapeRef;
/// Reference counted Areal
typedef std::shared_ptr<VectorAreal> VectorArealRef;
/// Reference counted Linear
typedef std::shared_ptr<VectorLinear> VectorLinearRef;
/// Reference counted Linear3d
typedef std::shared_ptr<VectorLinear3d> VectorLinear3dRef;
/// Reference counted Points
typedef std::shared_ptr<VectorPoints> VectorPointsRef;
/// Reference counted triangle mesh
typedef std::shared_ptr<VectorTriangles> VectorTrianglesRef;

/// Vector Ring is just a vector of 2D points
typedef Point2fVector VectorRing;

/// Vector Ring of 3D doubles
typedef Point3dVector VectorRing3d;

/// Comparison function for the vector shape.
/// This is here to ensure we don't put in the same pointer twice
//struct VectorShapeRefLess : std::less<VectorShape*>
//{
//    typedef std::less<VectorShape*> super;
//    bool operator()(const VectorShapeRef &a,const VectorShapeRef &b) const {
//        return super::operator()(a.get(), b.get());
//    }
//};
struct VectorShapeRefEqual : std::equal_to<VectorShape*>
{
    typedef std::equal_to<VectorShape*> super;
    bool operator()(const VectorShapeRef &a,const VectorShapeRef &b) const {
        return super::operator()(a.get(), b.get());
    }
};
struct VectorShapeRefHash : std::hash<VectorShape*>
{
    typedef std::hash<VectorShape*> super;
    size_t operator()(const VectorShapeRef &s) const {
        return super::operator()(s.get());
    }
};
  
/// We pass the shape set around when returning a group of shapes.
/// It's a set of reference counted shapes.  You have to dynamically
/// cast to get the specfic type.  Don't forget to use the std dynamic cast
//typedef std::set<VectorShapeRef,VectorShapeRefLess> ShapeSet;
typedef std::unordered_set<VectorShapeRef, VectorShapeRefHash, VectorShapeRefEqual> ShapeSet;
    
/// Calculate area of a loop
template <typename T> double CalcLoopArea(const std::vector<T,Eigen::aligned_allocator<T>>&);

/// Calculate the centroid of a loop
template <typename T> T CalcLoopCentroid(const std::vector<T,Eigen::aligned_allocator<T>>&);

/// Calculate the centroid of a loop when the area is already known
template <typename T> T CalcLoopCentroid(const std::vector<T,Eigen::aligned_allocator<T>>&, double loopArea);

/// Calculate the center of mass of the points
template <typename T> T CalcCenterOfMass(const std::vector<T,Eigen::aligned_allocator<T>> &loop);

extern template double CalcLoopArea<Point2f>(const VectorRing&);
extern template double CalcLoopArea<Point2d>(const Point2dVector&);
extern template Point2f CalcLoopCentroid(const VectorRing&);
extern template Point2d CalcLoopCentroid(const Point2dVector&);
extern template Point2f CalcLoopCentroid(const VectorRing&, double);
extern template Point2d CalcLoopCentroid(const Point2dVector&, double);
extern template Point2f CalcCenterOfMass(const VectorRing&);
extern template Point2d CalcCenterOfMass(const Point2dVector&);

/// Collection of triangles forming a mesh
class VectorTriangles : public VectorShape
{
public:
    EIGEN_MAKE_ALIGNED_OPERATOR_NEW;
    
    /// Creation function.  Use this instead of new.
    static VectorTrianglesRef createTriangles();
    ~VectorTriangles() = default;
    
    /// Simple triangle with three points (obviously)
    typedef struct Triangle
    {
        int pts[3];
    } Triangle;
    
    virtual GeoMbr calcGeoMbr();
    void initGeoMbr();
    
    // Return the given triangle as a VectorRing
    bool getTriangle(int which,Point2f points[3]) const;
    bool getTriangle(int which,VectorRing &ring) const;

    /// True if the given point is within one of the triangles
    bool pointInside(const GeoCoord &coord) const;
    
    // Bounding box in 2D
    GeoMbr geoMbr;

    // Shared points
    Point3fVector pts;
    // Triangles
    std::vector<Triangle> tris;

    /// Set to true if the coordinates have already been converted from geographic to local.
    bool localCoords = false;

protected:
    VectorTriangles() = default;
};

/// Look for a triangle/ray intersection in the mesh
bool VectorTrianglesRayIntersect(const Point3d &org,const Point3d &dir,
                                 const VectorTriangles &mesh,double *outT,Point3d *iPt);

/// Areal feature is a list of loops.  The first is an outer loop
///  and all the rest are inner loops
class VectorAreal : public VectorShape
{
public:
    EIGEN_MAKE_ALIGNED_OPERATOR_NEW;
    
    /// Creation function.  Use this instead of new
    static VectorArealRef createAreal();
    ~VectorAreal();
    
    virtual GeoMbr calcGeoMbr();
    void initGeoMbr();
    
    /// True if the given point is within one of the loops
    bool pointInside(GeoCoord coord);
    
    /// Sudivide to the given tolerance (in degrees)
    void subdivide(float tolerance);
        
    /// Bounding box in geographic coordinates.
	GeoMbr geoMbr;
	std::vector<VectorRing> loops;
    
protected:
    VectorAreal();
};

/// Linear feature is just a list of points that form
///  a set of edges
class VectorLinear : public VectorShape
{
public:
    EIGEN_MAKE_ALIGNED_OPERATOR_NEW;
    
    /// Creation function.  Use instead of new
    static VectorLinearRef createLinear();
    ~VectorLinear();
    
    virtual GeoMbr calcGeoMbr();
    void initGeoMbr();

    /// Sudivide to the given tolerance (in degrees)
    void subdivide(float tolerance);

	GeoMbr geoMbr;
	VectorRing pts;
    
protected:
    VectorLinear();
};

/// Linear feature is just a list of points that form
///  a set of edges.  This version has z as well.
class VectorLinear3d : public VectorShape
{
public:
    EIGEN_MAKE_ALIGNED_OPERATOR_NEW;

    /// Creation function.  Use instead of new
    static VectorLinear3dRef createLinear();
    ~VectorLinear3d();
    
    virtual GeoMbr calcGeoMbr();
    void initGeoMbr();
        
    GeoMbr geoMbr;
    VectorRing3d pts;
    
protected:
    VectorLinear3d();
};

/// The Points feature is a list of points that share attributes
///  and are otherwise unrelated.  In most cases you'll get one
///  point, but be prepared for multiple.
class VectorPoints : public VectorShape
{
public:
    EIGEN_MAKE_ALIGNED_OPERATOR_NEW;
    
    /// Creation function.  Use instead of new
    static VectorPointsRef createPoints();
    ~VectorPoints();
    
    /// Return the bounding box
    virtual GeoMbr calcGeoMbr();
    
    /// Calculate the bounding box from data
    void initGeoMbr();

	GeoMbr geoMbr;
	VectorRing pts;
    
protected:
    VectorPoints();
};
    
/// A set of strings
typedef std::set<std::string> StringSet;

/// Break any edge longer than the given length.
void SubdivideEdges(const VectorRing &inPts,VectorRing &outPts,bool closed,float maxLen);
void SubdivideEdges(const VectorRing3d &inPts,VectorRing3d &outPts,bool closed,float maxLen);

/// Break any edge that deviates by the given epsilon from the surface described in
/// the display adapter;
void SubdivideEdgesToSurface(const VectorRing &inPts,VectorRing &outPts,bool closed,
                             const CoordSystemDisplayAdapter *adapter,float eps);
void SubdivideEdgesToSurface(const VectorRing3d &inPts,VectorRing3d &outPts,bool closed,
                             const CoordSystemDisplayAdapter *adapter,float eps);

/// Break any edge that deviates by the given epsilon from the surface described in
///  the display adapter.  But rather than using lat lon values, we'll output in
///  display coordinates and build points along the great circle.
void SubdivideEdgesToSurfaceGC(const VectorRing &inPts,VectorRing3d &outPts,bool closed,
                               const CoordSystemDisplayAdapter *adapter,
                               float eps,float sphereOffset = 0.0,int minPts = 0);
    
/** Base class for loading a vector data file.
 Fill this into hand data over to whomever wants it.
 */
class VectorReader
{
public:
    VectorReader() { }
    virtual ~VectorReader() { }
    
    /// Return false if we failed to load
    virtual bool isValid() = 0;
    
    /// Returns one of the vector types.
    /// Keep enough state to figure out what the next one is.
    /// You can skip any attributes not named in the filter.  Or just ignore it.
    virtual VectorShapeRef getNextObject(const StringSet *filter) = 0;
    
    /// Return true if this vector reader can seek and read
    virtual bool canReadByIndex() { return false; }
    
    /// Return the total number of vectors objects
    virtual unsigned int getNumObjects() { return 0; }
    
    /// Return an object that corresponds to the given index.
    /// You need to be able to seek in your file format for this.
    /// The filter works the same as for getNextObect()
    virtual VectorShapeRef getObjectByIndex(unsigned int vecIndex,const StringSet *filter)  { return VectorShapeRef(); }
};

bool VectorReadFile(const std::string &fileName,ShapeSet &shapes);
bool VectorWriteFile(const std::string &fileName,ShapeSet &shapes);

/** Helper routine to parse geoJSON into a collection of vectors.
 We don't know for sure what we'll get back, so you have to go
 looking through it.  Return false on parse failure.
 */
bool VectorParseGeoJSON(ShapeSet &shapes,const std::string &str,std::string &crs);

/** Helper routine to parse a GeoJSON assembly into an array of
 collections of vectors.  This format is returned by the experimental
 OSM server for vectors.
 */
bool VectorParseGeoJSONAssembly(const std::string &str,std::map<std::string,ShapeSet> &shapes);

}

