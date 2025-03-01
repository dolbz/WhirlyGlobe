/*  VectorData.cpp
 *  WhirlyGlobeLib
 *
 *  Created by Steve Gifford on 3/7/11.
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

#import <string>
#import "VectorData.h"
#import "ShapeReader.h"
#import "WhirlyKitLog.h"
#import "libjson.h"

namespace WhirlyKit
{

template <typename T>
double CalcLoopArea(const std::vector<T,Eigen::aligned_allocator<T>> &loop)
{
    if (loop.empty())
    {
        return 0;
    }
    // If the loop returns to the initial point, stop there.
    // If it does not, force it to be closed by re-considering the first point.
    const bool closed = !loop.empty() && loop.front() == loop.back();
    const auto loopSize = loop.size();
    const auto maxIter = closed ? loopSize - 1 : loopSize;

    double area = 0.0;
    for (unsigned int ii=0;ii<maxIter;ii++)
    {
        const auto &p1 = loop[ii];
        const auto &p2 = loop[(ii+1)%loopSize];
        area += p1.x()*p2.y() - p1.y()*p2.x();
    }
    return area;
}

// Calculate the centroid of a loop when the area is already known
template <typename T>
T CalcLoopCentroid(const std::vector<T,Eigen::aligned_allocator<T>> &loop, double loopArea)
{
    if (loop.empty())
    {
        return {0,0};
    }
    // Area must be positive or negative, not zero or NaN, etc.
    if (loopArea == 0 || !isfinite(loopArea))
    {
        assert(!"invalid loop area");
        return {0,0};
    }

    // If the loop closes back to the initial point, stop there.
    // If it does not, force it to be closed by re-considering the first point.
    const bool closed = !loop.empty() && loop.front() == loop.back();
    const auto loopSize = loop.size();
    const auto maxIter = closed ? loopSize - 1 : loopSize;

    double sumX = 0, sumY = 0;
    for (unsigned int ii=0;ii<maxIter;ii++)
    {
        const auto &p0 = loop[ii];
        const auto &p1 = loop[(ii+1)%loopSize];
        const double b = (p0.x()*p1.y()-p1.x()*p0.y());
        sumX += (p0.x()+p1.x())*b;
        sumY += (p0.y()+p1.y())*b;
    }

    return {static_cast<typename T::Scalar>(sumX/(3*loopArea)),
            static_cast<typename T::Scalar>(sumY/(3*loopArea))};
}

// Calculate the centroid of an arbitrary loop
template <typename T>
T CalcLoopCentroid(const std::vector<T,Eigen::aligned_allocator<T>> &loop)
{
    return CalcLoopCentroid(loop, CalcLoopArea(loop));
}

template <typename T>
T CalcCenterOfMass(const std::vector<T,Eigen::aligned_allocator<T>> &loop)
{
    if (loop.empty()) {
        return {0,0};
    }

    double cx = 0, cy = 0;
    for (const auto &pt : loop) {
        cx += pt.x();
        cy += pt.y();
    }

    return {static_cast<typename T::Scalar>(cx/loop.size()),
            static_cast<typename T::Scalar>(cy/loop.size())};
}

// Export specific instantiations of the templates above.
template double CalcLoopArea<Point2f>(const VectorRing&);
template double CalcLoopArea<Point2d>(const Point2dVector&);
template Point2f CalcLoopCentroid(const VectorRing&);
template Point2d CalcLoopCentroid(const Point2dVector&);
template Point2f CalcLoopCentroid(const VectorRing&, double);
template Point2d CalcLoopCentroid(const Point2dVector&, double);
template Point2f CalcCenterOfMass(const VectorRing&);
template Point2d CalcCenterOfMass(const Point2dVector&);

// Break any edge longer than the given length
void SubdivideEdges(const VectorRing &inPts,VectorRing &outPts,bool closed,float maxLen)
{
    const float maxLen2 = maxLen*maxLen;

    if (outPts.empty())
    {
        outPts.reserve(2 * inPts.size());
    }
    
    for (int ii=0;ii<(closed ? inPts.size() : inPts.size()-1);ii++)
    {
        const Point2f &p0 = inPts[ii];
        const Point2f &p1 = inPts[(ii+1)%inPts.size()];
        outPts.push_back(p0);
        Point2f dir = p1-p0;
        const float dist2 = dir.squaredNorm();
        if (dist2 > maxLen2)
        {
            const float dist = sqrtf(dist2);
            dir /= dist;
            for (float pos=maxLen;pos<dist;pos+=maxLen)
            {
                outPts.emplace_back(p0+dir*pos);
            }
        }
    }
    if (!closed)
        outPts.push_back(inPts.back());
}

void SubdivideEdges(const VectorRing3d &inPts,VectorRing3d &outPts,bool closed,float maxLen)
{
    const float maxLen2 = maxLen*maxLen;

    if (outPts.empty())
    {
        outPts.reserve(2 * inPts.size());
    }

    for (int ii=0;ii<(closed ? inPts.size() : inPts.size()-1);ii++)
    {
        const Point3d &p0 = inPts[ii];
        const Point3d &p1 = inPts[(ii+1)%inPts.size()];
        outPts.push_back(p0);
        Point3d dir = p1-p0;
        const float dist2 = dir.squaredNorm();
        if (dist2 > maxLen2)
        {
            const float dist = sqrtf(dist2);
            dir /= dist;
            for (float pos=maxLen;pos<dist;pos+=maxLen)
            {
                outPts.push_back(p0+dir*pos);
            }
        }
    }
    if (!closed)
        outPts.push_back(inPts.back());
}

void subdivideToSurfaceRecurse(const Point2f &p0,const Point2f &p1,VectorRing &outPts,
                               const CoordSystemDisplayAdapter *adapter,double eps2,
                               double prevDist2 = std::numeric_limits<double>::max())
{
    // If the difference is greater than 180, then this is probably crossing the date line
    //  in which case we'll just leave it alone.
    if (std::abs(p0.x() - p1.x()) > M_PI)
        return;

    const auto coordSys = adapter->getCoordSystem();
    const Point3f dp0 = adapter->localToDisplay(coordSys->geographicToLocal(GeoCoord(p0.x(),p0.y())));
    const Point3f dp1 = adapter->localToDisplay(coordSys->geographicToLocal(GeoCoord(p1.x(),p1.y())));
    const Point2f midPt = (p0+p1)/2.0;
    const Point3f dMidPt = adapter->localToDisplay(coordSys->geographicToLocal(GeoCoord(midPt.x(),midPt.y())));
    const Point3f halfPt = (dp0+dp1)/2.0;
    const auto dist2 = (halfPt-dMidPt).squaredNorm();
    // Recurse until the distance threshold is met, or until the distance stops decreasing
    if (dist2 > eps2 && dist2 < prevDist2)
    {
        subdivideToSurfaceRecurse(p0, midPt, outPts, adapter, eps2, dist2);
        subdivideToSurfaceRecurse(midPt, p1, outPts, adapter, eps2, dist2);
    }
    if (outPts.empty() || outPts.back() != p1)
         outPts.push_back(p1);
}

void subdivideToSurfaceRecurse(const Point3d &p0,const Point3d &p1,VectorRing3d &outPts,
                               const CoordSystemDisplayAdapter *adapter,double eps2,
                               double prevDist2 = std::numeric_limits<double>::max())
{
    // If the difference is greater than 180, then this is probably crossing the date line
    //  in which case we'll just leave it alone.
    if (std::abs(p0.x() - p1.x()) > M_PI)
        return;

    const auto coordSys = adapter->getCoordSystem();
    const Point3d dp0 = adapter->localToDisplay(coordSys->geographicToLocal3d(GeoCoord(p0.x(),p0.y())));
    const Point3d dp1 = adapter->localToDisplay(coordSys->geographicToLocal3d(GeoCoord(p1.x(),p1.y())));
    const Point3d midPt = (p0+p1)/2.0;
    const Point3d dMidPt = adapter->localToDisplay(coordSys->geographicToLocal3d(GeoCoord(midPt.x(),midPt.y())));
    const Point3d halfPt = (dp0+dp1)/2.0;
    const auto dist2 = (halfPt-dMidPt).squaredNorm();
    // Recurse until the distance threshold is met, or until the distance stops decreasing
    if (dist2 > eps2 && dist2 < prevDist2)
    {
        subdivideToSurfaceRecurse(p0, midPt, outPts, adapter, eps2, dist2);
        subdivideToSurfaceRecurse(midPt, p1, outPts, adapter, eps2, dist2);
    }
    outPts.push_back(p1);
}

void SubdivideEdgesToSurface(const VectorRing &inPts,VectorRing &outPts,bool closed,
        const CoordSystemDisplayAdapter *adapter,float eps)
{
    const auto eps2 = (double)eps * eps;
    for (int ii=0;ii<(closed ? inPts.size() : inPts.size()-1);ii++)
    {
        const Point2f &p0 = inPts[ii];
        const Point2f &p1 = inPts[(ii+1)%inPts.size()];
        if (outPts.empty() || outPts.back() != p0)
            outPts.push_back(p0);
        subdivideToSurfaceRecurse(p0,p1,outPts,adapter,eps2);
    }
}

void SubdivideEdgesToSurface(const VectorRing3d &inPts,VectorRing3d &outPts,bool closed,
                             const CoordSystemDisplayAdapter *adapter,float eps)
{
    const auto eps2 = (double)eps * eps;
    for (int ii=0;ii<(closed ? inPts.size() : inPts.size()-1);ii++)
    {
        const Point3d &p0 = inPts[ii];
        const Point3d &p1 = inPts[(ii+1)%inPts.size()];
        outPts.push_back(p0);
        subdivideToSurfaceRecurse(p0,p1,outPts,adapter,eps2);
    }
}

// Great circle version
void subdivideToSurfaceRecurseGC(const Point3d &p0,const Point3d &p1,Point3dVector &outPts,
        const CoordSystemDisplayAdapter *adapter,double eps2,float surfOffset,int minPts,
        double prevDist2 = std::numeric_limits<double>::max())
{
    const Point3d midP = (p0+p1)/2.0;
    const Point3d midOnSphere = (adapter && !adapter->isFlat()) ? (midP.normalized() * (1.0 + surfOffset)) : midP;
    const auto dist2 = (midOnSphere - midP).squaredNorm();
    if ((dist2 > eps2 || minPts > 0) && dist2 < prevDist2)
    {
        subdivideToSurfaceRecurseGC(p0, midOnSphere, outPts, adapter, eps2, surfOffset,minPts/2,dist2);
        subdivideToSurfaceRecurseGC(midOnSphere, p1, outPts, adapter, eps2, surfOffset,minPts/2,dist2);
    }
    if (outPts.empty() || outPts.back() != p1)
        outPts.push_back(p1);
}

void SubdivideEdgesToSurfaceGC(const VectorRing &inPts,Point3dVector &outPts,bool closed,
        const CoordSystemDisplayAdapter *adapter,float eps,float surfOffset,int minPts)
{
    if (!adapter || inPts.empty())
        return;
    const auto coordSys = adapter->getCoordSystem();
    if (inPts.size() < 2)
    {
        const Point2f &p0 = inPts[0];
        const Point3d dp0 = adapter->localToDisplay(coordSys->geographicToLocal3d(GeoCoord(p0.x(),p0.y())));
        outPts.push_back(dp0);
        return;
    }

    const auto eps2 = (double)eps * eps;
    for (int ii=0;ii<(closed ? inPts.size() : inPts.size()-1);ii++)
    {
        const Point2f &p0 = inPts[ii];
        const Point2f &p1 = inPts[(ii+1)%inPts.size()];
        Point3d dp0 = adapter->localToDisplay(coordSys->geographicToLocal3d(GeoCoord(p0.x(),p0.y())));
        if (adapter && !adapter->isFlat())
           dp0 = dp0.normalized() * (1.0 + surfOffset);
        Point3d dp1 = adapter->localToDisplay(coordSys->geographicToLocal3d(GeoCoord(p1.x(),p1.y())));
        if (adapter && !adapter->isFlat())
           dp1 = dp1.normalized() * (1.0 + surfOffset);
        outPts.push_back(dp0);
        subdivideToSurfaceRecurseGC(dp0,dp1,outPts,adapter,eps2,surfOffset,minPts);
    }
}

VectorShape::VectorShape()
{
    attrDict = MutableDictionaryMake();
}
   
VectorShape::~VectorShape() = default;
    
void VectorShape::setAttrDict(MutableDictionaryRef newDict)
{
    attrDict = std::move(newDict);
}
    
MutableDictionaryRef VectorShape::getAttrDict() const
{
    return attrDict;
}

const MutableDictionaryRef &VectorShape::getAttrDictRef() const
{
    return attrDict;
}

VectorTrianglesRef VectorTriangles::createTriangles()
{
    return VectorTrianglesRef(new VectorTriangles());
}
    
GeoMbr VectorTriangles::calcGeoMbr()
{
    if (!geoMbr.valid())
        initGeoMbr();
    return geoMbr;
}
    
bool VectorTriangles::pointInside(const GeoCoord &coord) const
{
    if (geoMbr.inside(coord))
    {
        VectorRing ring;
        for (int ti=0;ti<tris.size();ti++)
        {
            ring.clear();
            getTriangle(ti, ring);
            if (PointInPolygon(coord, ring))
            {
                return true;
            }
        }
    }
    
    return false;
}

bool VectorTriangles::getTriangle(int which, Point2f points[3]) const
{
    if (0 <= which && which < tris.size())
    {
        const auto t = tris[which].pts;
        points[0] = Slice(pts[t[0]]);
        points[1] = Slice(pts[t[1]]);
        points[2] = Slice(pts[t[2]]);
        return true;
    }
    return false;
}

bool VectorTriangles::getTriangle(int which,VectorRing &ring) const
{
    if (which < 0 || which >= tris.size())
        return false;

    ring.resize(3);
    return getTriangle(which, &ring[0]);
}

void VectorTriangles::initGeoMbr()
{
    geoMbr.addGeoCoords(pts);
}

bool VectorTrianglesRayIntersect(const Point3d &org,const Point3d &dir,const VectorTriangles &mesh,
                                 double *outT,Point3d *iPt)
{
    double tMin = std::numeric_limits<double>::max();
    Point3d minPt {0,0,0};
    
    // Look for closest intersection
    for (const auto &tri : mesh.tris)
    {
        Point3d pts[3];
        for (int jj=0;jj<3;jj++)
        {
            const Point3f &pt = mesh.pts[tri.pts[jj]];
            pts[jj] = Point3d(pt.x(),pt.y(),pt.z());
        }

        double thisT;
        Point3d thisPt;
        if (TriangleRayIntersection(org, dir, pts, &thisT, &thisPt))
        {
            if (thisT < tMin)
            {
                tMin = thisT;
                minPt = thisPt;
            }
        }
    }
    
    if (tMin != std::numeric_limits<double>::max())
    {
        if (outT)
            *outT = tMin;
        if (iPt)
            *iPt = minPt;
        return true;
    }
    
    return false;
}

VectorAreal::VectorAreal() = default;
    
VectorAreal::~VectorAreal() = default;
    
VectorArealRef VectorAreal::createAreal()
{
    // TODO: causes an error, can we `friend std::make_shared`?
    //return std::make_shared<VectorAreal>();
    return VectorArealRef(new VectorAreal());
}

    
bool VectorAreal::pointInside(GeoCoord coord)
{
    if (geoMbr.inside(coord))
    {
        for (auto & loop : loops)
            if (PointInPolygon(coord,loop))
                return true;
    }
    
    return false;
}
    
GeoMbr VectorAreal::calcGeoMbr() 
{ 
    if (!geoMbr.valid())
        initGeoMbr();
    return geoMbr; 
}
    
void VectorAreal::initGeoMbr()
{
    for (auto & loop : loops)
        geoMbr.addGeoCoords(loop);
}
    
void VectorAreal::subdivide(float maxLen)
{
    for (auto & loop : loops)
    {
        VectorRing newPts;
        SubdivideEdges(loop, newPts, true, maxLen);
        loop = newPts;
    }
}

VectorLinear::VectorLinear() = default;

VectorLinear::~VectorLinear() = default;

VectorLinearRef VectorLinear::createLinear()
{
    return VectorLinearRef(new VectorLinear());
}
    
GeoMbr VectorLinear::calcGeoMbr() 
{ 
    if (!geoMbr.valid())
        initGeoMbr();
    return geoMbr; 
}

void VectorLinear::initGeoMbr()
{
    geoMbr.addGeoCoords(pts);
}
    
void VectorLinear::subdivide(float maxLen)
{
    VectorRing newPts;
    SubdivideEdges(pts, newPts, false, maxLen);
    pts = newPts;
}
    
VectorLinear3d::VectorLinear3d() = default;

VectorLinear3d::~VectorLinear3d() = default;

VectorLinear3dRef VectorLinear3d::createLinear()
{
    return VectorLinear3dRef(new VectorLinear3d());
}

GeoMbr VectorLinear3d::calcGeoMbr()
{
    if (!geoMbr.valid())
        initGeoMbr();
    return geoMbr;
}

void VectorLinear3d::initGeoMbr()
{
    geoMbr.addGeoCoords(pts);
}

VectorPoints::VectorPoints() = default;
    
VectorPoints::~VectorPoints() = default;
    
VectorPointsRef VectorPoints::createPoints()
{
    //return std::make_shared<VectorPoints>();
    return VectorPointsRef(new VectorPoints());
}

GeoMbr VectorPoints::calcGeoMbr() 
{ 
    if (!geoMbr.valid())
        initGeoMbr();
    return geoMbr;
}

void VectorPoints::initGeoMbr()
{
    geoMbr.addGeoCoords(pts);
}
 
#if 0
typedef enum {FileVecPoints=20,FileVecLinear,FileVecAreal,FileVecMesh} VectorIdentType;

bool VectorWriteFile(const std::string &fileName,ShapeSet &shapes)
{
    FILE *fp = fopen(fileName.c_str(),"w");
    if (!fp)
        return false;
    
    try {
        int numFeatures = (int)shapes.size();
        if (fwrite(&numFeatures,sizeof(int),1, fp) != 1)
            throw 1;
        
        for (ShapeSet::iterator it = shapes.begin(); it != shapes.end(); ++it)
        {
            VectorShapeRef shape = *it;
            
            // They all have a dictionary
            MutableDictionaryRef dict = shape->getAttrDict();
            MutableRawData dictData;
            dict->asRawData(&dictData);
            int dataLen = (int)dictData.getLen();
            if (fwrite(&dataLen,sizeof(int),1,fp) != 1)
                throw 1;
            if (dataLen > 0)
                if (fwrite(dictData.getRawData(),dataLen,1,fp) != 1)
                    throw 1;
            
            VectorPointsRef pts = std::dynamic_pointer_cast<VectorPoints>(shape);
            VectorLinearRef lin = std::dynamic_pointer_cast<VectorLinear>(shape);
            VectorArealRef ar = std::dynamic_pointer_cast<VectorAreal>(shape);
            VectorTrianglesRef mesh = std::dynamic_pointer_cast<VectorTriangles>(shape);
            if (pts.get())
            {
                unsigned short dataType = FileVecPoints;
                if (fwrite(&dataType,sizeof(short),1,fp) != 1)
                    throw 1;
                
                unsigned int numPts = (int)pts->pts.size();
                if (fwrite(&numPts,sizeof(unsigned int),1,fp) != 1)
                    throw 1;
                if (fwrite(&pts->pts[0],2*sizeof(float),numPts,fp) != numPts)
                    throw 1;
            } else if (lin.get())
            {
                unsigned short dataType = FileVecLinear;
                if (fwrite(&dataType,sizeof(short),1,fp) != 1)
                    throw 1;
                
                unsigned int numPts = (unsigned int)lin->pts.size();
                if (fwrite(&numPts,sizeof(unsigned int),1,fp) != 1)
                    throw 1;
                if (fwrite(&lin->pts[0],2*sizeof(float),numPts,fp) != numPts)
                    throw 1;
                
            } else if (ar.get())
            {
                unsigned short dataType = FileVecAreal;
                if (fwrite(&dataType,sizeof(short),1,fp) != 1)
                    throw 1;
                
                unsigned int numLoops = (unsigned int)ar->loops.size();
                if (fwrite(&numLoops,sizeof(int),1,fp) != 1)
                    throw 1;
                for (unsigned int ii=0;ii<numLoops;ii++)
                {
                    VectorRing &ring = ar->loops[ii];
                    unsigned int numPts = (unsigned int)ring.size();
                    if (fwrite(&numPts,sizeof(unsigned int),1,fp) != 1)
                        throw 1;
                    if (fwrite(&ring[0],2*sizeof(float),numPts,fp) != numPts)
                        throw 1;
                }
                
            } else if (mesh.get())
            {
                unsigned short dataType = FileVecMesh;
                if (fwrite(&dataType,sizeof(short),1,fp) != 1)
                    throw 1;
                
                unsigned int numPts = (unsigned int)mesh->pts.size();
                if (fwrite(&numPts,sizeof(unsigned int),1,fp) != 1)
                    throw 1;
                if (fwrite(&mesh->pts[0],3*sizeof(float),numPts,fp) != numPts)
                    throw 1;
                
                unsigned int numTri = (unsigned int)mesh->tris.size();
                if (fwrite(&numTri,sizeof(unsigned int),1,fp) != 1)
                    throw 1;
                if (fwrite(&mesh->tris[0],3*sizeof(unsigned int),numTri,fp) != numTri)
                    throw 1;
            } else {
                //                NSLog(@"Tried to write unknown object in VectorWriteFile");
                throw 1;
            }
        }
    }
    catch (...)
    {
        fclose(fp);
        return false;
    }
    
    fclose(fp);
    return true;
}

bool VectorReadFile(const std::string &fileName,ShapeSet &shapes)
{
    FILE *fp = fopen(fileName.c_str(),"r");
    if (!fp)
        return false;
    
    try {
        int numFeatures;
        if (fread(&numFeatures, sizeof(int), 1, fp) != 1)
            throw 1;
        
        for (unsigned int ii=0;ii<numFeatures;ii++)
        {
            // Dictionary first
            int dataLen;
            if (fread(&dataLen, sizeof(int), 1, fp) != 1)
                throw 1;
            Dictionary *dict = NULL;
            if (dataLen > 0)
            {
                RawDataWrapper *rawData = RawDataFromFile(fp, dataLen);
                if (!rawData)
                    throw 1;
                dict = new Dictionary(rawData);
                delete rawData;
            }
            
            // Now for the type
            unsigned short dataType;
            if (fread(&dataType,sizeof(unsigned short),1,fp) != 1)
                throw 1;
            
            switch (dataType)
            {
                case FileVecPoints:
                {
                    VectorPointsRef pts(VectorPoints::createPoints());
                    if (dict)
                        pts->setAttrDict(*dict);
                    
                    unsigned int numPts;
                    if (fread(&numPts,sizeof(unsigned int),1,fp) != 1)
                        throw 1;
                    pts->pts.resize(numPts);
                    if (fread(&pts->pts[0],2*sizeof(float),numPts,fp) != numPts)
                        throw 1;
                    
                    pts->initGeoMbr();
                    shapes.insert(pts);
                }
                    break;
                case FileVecLinear:
                {
                    VectorLinearRef lin(VectorLinear::createLinear());
                    if (dict)
                        lin->setAttrDict(*dict);
                    
                    unsigned int numPts;
                    if (fread(&numPts,sizeof(unsigned int),1,fp) != 1)
                        throw 1;
                    lin->pts.resize(numPts);
                    if (fread(&lin->pts[0],2*sizeof(float),numPts,fp) != numPts)
                        throw 1;
                    
                    lin->initGeoMbr();
                    shapes.insert(lin);
                }
                    break;
                case FileVecAreal:
                {
                    VectorArealRef ar(VectorAreal::createAreal());
                    if (dict)
                        ar->setAttrDict(*dict);
                    
                    unsigned int numLoops;
                    if (fread(&numLoops,sizeof(unsigned int),1,fp) != 1)
                        throw 1;
                    ar->loops.resize(numLoops);
                    
                    for (unsigned int ii=0;ii<numLoops;ii++)
                    {
                        VectorRing &ring = ar->loops[ii];
                        unsigned int numPts;
                        if (fread(&numPts,sizeof(unsigned int),1,fp) != 1)
                            throw 1;
                        ring.resize(numPts);
                        if (fread(&ring[0],2*sizeof(float),numPts,fp) != numPts)
                            throw 1;
                    }
                    
                    ar->initGeoMbr();
                    shapes.insert(ar);
                }
                    break;
                case FileVecMesh:
                {
                    VectorTrianglesRef mesh(VectorTriangles::createTriangles());
                    if (dict)
                        mesh->setAttrDict(*dict);
                    
                    unsigned int numPts;
                    if (fread(&numPts,sizeof(unsigned int),1,fp) != 1)
                        throw 1;
                    mesh->pts.resize(numPts);
                    if (fread(&mesh->pts[0],3*sizeof(float),numPts,fp) != numPts)
                        throw 1;
                    
                    unsigned int numTri;
                    if (fread(&numTri,sizeof(unsigned int),1,fp) != 1)
                        throw 1;
                    mesh->tris.resize(numTri);
                    if (fread(&mesh->tris[0],3*sizeof(unsigned int),numTri,fp) != numTri)
                        throw 1;
                    
                    mesh->initGeoMbr();
                    shapes.insert(mesh);
                }
                    break;
                default:
                    //                    NSLog(@"Unknown data type in VectorReadFile()");
                    throw 1;
                    break;
            }
            
            if (dict)
                delete dict;
        }
    }
    catch (...)
    {
        fclose(fp);
        return false;
    }
    
    fclose(fp);
    return true;
}
#endif

using namespace libjson;

// Parse properties out of a node
static bool VectorParseProperties(JSONNode node,const MutableDictionaryRef &dict)
{
    for (JSONNode::const_iterator it = node.begin();
         it != node.end(); ++it)
    {
        json_string name = it->name();
        if (!name.empty())
        {
            switch (it->type())
            {
                case JSON_STRING:
                {
                    json_string val = it->as_string();
                    dict->setString(name,val);
                }
                    break;
                case JSON_NUMBER:
                {
                    double val = it->as_float();
                    dict->setDouble(name, val);
                }
                    break;
                case JSON_BOOL:
                {
                    bool val = it->as_bool();
                    dict->setInt(name, (int)val);
                }
                    break;
            }
        }
    }
    
    return true;
}
    
// Parse coordinate list out of a node
bool VectorParseCoordinates(JSONNode node,VectorRing &pts, bool subCall=false)
{
    for (JSONNode::const_iterator it = node.begin();
         it != node.end(); ++it)
    {
        if (it->type() == JSON_ARRAY)
        {
            if (!VectorParseCoordinates(*it, pts, true))
                return false;
            continue;
        }
        
        // We're expecting two numbers here
        if (it->type() == JSON_NUMBER)
        {
            if (node.size() < 2)
                return false;
            
            float lon = it->as_float();  ++it;
            float lat = it->as_float();
            pts.push_back(GeoCoord::CoordFromDegrees(lon,lat));
            
            // There might be a Z value or even other junk.  We just want the first two coordinates
            //  in this particular case.
            if (subCall)
                return true;
            
            continue;
        }
        
        // Got something unexpected
        return false;
    }
    
    return true;
}

// Parse geometry out of a node
bool VectorParseGeometry(JSONNode node,ShapeSet &shapes)
{
    // Let's look for type and coordinates
    JSONNode::const_iterator typeIt = node.end();
    JSONNode::const_iterator coordIt = node.end();
    JSONNode::const_iterator geomCollectIt = node.end();
    for (JSONNode::const_iterator it = node.begin();
         it != node.end(); ++it)
    {
        if (!it->name().compare("type"))
            typeIt = it;
        else if (!it->name().compare("coordinates"))
            coordIt = it;
        else if (!it->name().compare("geometries"))
            geomCollectIt = it;
    }
    
    if (typeIt == node.end())
        return false;
    
    json_string type = typeIt->as_string();
    if (!type.compare("Point"))
    {
        if (coordIt == node.end() || coordIt->type() != JSON_ARRAY)
            return false;

        VectorPointsRef pts = VectorPoints::createPoints();
        if (!VectorParseCoordinates(*coordIt,pts->pts))
            return false;
        pts->initGeoMbr();
        shapes.insert(pts);
        
        return true;
    } else if (!type.compare("LineString"))
    {
        if (coordIt == node.end() || coordIt->type() != JSON_ARRAY)
            return false;
        
        VectorLinearRef lin = VectorLinear::createLinear();
        if (!VectorParseCoordinates(*coordIt,lin->pts))
            return false;
        lin->initGeoMbr();
        shapes.insert(lin);

        return true;
    } else if (!type.compare("Polygon"))
    {
        // This should be an array of array of coordinates
        if (coordIt == node.end() || coordIt->type() != JSON_ARRAY)
            return false;
        VectorArealRef ar = VectorAreal::createAreal();
        int numLoops = 0;
        for (JSONNode::const_iterator coordEntryIt = coordIt->begin();
             coordEntryIt != coordIt->end(); ++coordEntryIt, numLoops++)
        {
            if (coordEntryIt->type() != JSON_ARRAY)
                return false;
        
            ar->loops.resize(numLoops+1);
            if (!VectorParseCoordinates(*coordEntryIt,ar->loops[numLoops]))
                return false;
        }
        
        ar->initGeoMbr();
        shapes.insert(ar);
        
        return true;
    } else if (!type.compare("MultiPoint"))
    {
        if (coordIt == node.end() || coordIt->type() != JSON_ARRAY)
            return false;
        
        VectorPointsRef pts = VectorPoints::createPoints();
        if (!VectorParseCoordinates(*coordIt,pts->pts))
            return false;
        pts->initGeoMbr();
        shapes.insert(pts);
        
        return true;        
    } else if (!type.compare("MultiLineString"))
    {
        // This should be an array of array of coordinates
        if (coordIt == node.end() || coordIt->type() != JSON_ARRAY)
            return false;
        for (JSONNode::const_iterator coordEntryIt = coordIt->begin();
             coordEntryIt != coordIt->end(); ++coordEntryIt)
        {
            if (coordEntryIt->type() != JSON_ARRAY)
                return false;
            
            VectorLinearRef lin = VectorLinear::createLinear();
            if (!VectorParseCoordinates(*coordEntryIt, lin->pts))
                return false;
            lin->initGeoMbr();
            shapes.insert(lin);
        }
        
        return true;
    } else if (!type.compare("MultiPolygon"))
    {
        // This should be an array of array of coordinates
        if (coordIt == node.end() ||  coordIt->type() != JSON_ARRAY)
            return false;

        for (JSONNode::const_iterator polyIt = coordIt->begin();
             polyIt != coordIt->end(); ++polyIt)
        {
            VectorArealRef ar = VectorAreal::createAreal();
            int numLoops = 0;
            for (JSONNode::const_iterator coordEntryIt = polyIt->begin();
                 coordEntryIt != polyIt->end(); ++coordEntryIt, numLoops++)
            {
                if (coordEntryIt->type() != JSON_ARRAY)
                    return false;
                
                ar->loops.resize(numLoops+1);
                if (!VectorParseCoordinates(*coordEntryIt,ar->loops[numLoops]))
                    return false;
            }
            
            ar->initGeoMbr();
            shapes.insert(ar);
        }
        
        return true;        
    } else if (!type.compare("GeometryCollection"))
    {
        if (geomCollectIt == node.end() || geomCollectIt->type() != JSON_ARRAY)
            return false;
        for (JSONNode::const_iterator geomIt = geomCollectIt->begin();
             geomIt != geomCollectIt->end(); ++geomIt)
            if (!VectorParseGeometry(*geomIt,shapes))
                return false;
        
        return true;
    }
    
    return false;
}
    
// Parse a single feature
bool VectorParseFeature(JSONNode node,ShapeSet &shapes)
{
    JSONNode::const_iterator typeIt = node.end();
    JSONNode::const_iterator geomIt = node.end();
    JSONNode::const_iterator propIt = node.end();
    
    for (JSONNode::const_iterator it = node.begin();
         it != node.end(); ++it)
    {
        if (!it->name().compare("type"))
            typeIt = it;
        else if (!it->name().compare("geometry"))
            geomIt = it;
        else if (!it->name().compare("properties"))
            propIt = it;
    }
    if (geomIt == node.end())
        return false;
    
    // Parse the geometry
    ShapeSet newShapes;
    if (!VectorParseGeometry(*geomIt, newShapes))
        return false;

    // Properties are optional
    if (propIt != node.end()) {
        MutableDictionaryRef properties = MutableDictionaryMake();
        VectorParseProperties(*propIt, properties);
        // Apply the properties to the geometry
        for (const auto & newShape : newShapes)
            newShape->setAttrDict(properties);
    }
    
    shapes.insert(newShapes.begin(), newShapes.end());
    return true;
}

// Parse an array of features
bool VectorParseFeatures(JSONNode node,ShapeSet &shapes)
{
    for (JSONNode::const_iterator it = node.begin();it != node.end(); ++it) {
        // Not sure what this would be
        if (it->type() != JSON_NODE)
            return false;
        if (!VectorParseFeature(*it, shapes)) {
            return false;
        }
    }
    
    return true;
}

// Recursively parse a feature collection
bool VectorParseTopNode(JSONNode node,ShapeSet &shapes,JSONNode &crs)
{
    JSONNode::const_iterator typeIt = node.end();
    JSONNode::const_iterator featIt = node.end();
    
    for (JSONNode::const_iterator it = node.begin();
         it != node.end(); ++it)
    {
        if (!it->name().compare("type"))
            typeIt = it;
        else if (!it->name().compare("features"))
            featIt = it;
        else if (!it->name().compare("crs"))
            crs = *it;
    }
    if (typeIt == node.end())
        return false;
    
    json_string type;
    type = typeIt->as_string();
    if (!type.compare("FeatureCollection"))
    {
        // Expecting a features node
        if (featIt == node.end() || featIt->type() != JSON_ARRAY)
            return false;
        return VectorParseFeatures(*featIt,shapes);
    } else if (!type.compare("Feature"))
    {
        return VectorParseFeature(node,shapes);
    } else {
        // Only last try to do raw geometry
        return VectorParseGeometry(node, shapes);
    }

    return false;
}

// Parse the name out of a CRS in a GeoJSON file
bool VectorParseGeoJSONCRS(JSONNode node,std::string &crsName)
{
    JSONNode::const_iterator typeIt = node.end();
    JSONNode::const_iterator propIt = node.end();
    
    for (JSONNode::const_iterator it = node.begin();
         it != node.end(); ++it)
    {
        if (!it->name().compare("type"))
            typeIt = it;
        else if (!it->name().compare("properties"))
            propIt = it;
    }
    if (typeIt == node.end())
        return false;
    
    json_string type;
    type = typeIt->as_string();
    if (!type.compare("name"))
    {
        // Expecting a features node
        if (propIt == node.end() || propIt->type() != JSON_NODE)
            return false;
        
        for (JSONNode::const_iterator it = propIt->begin(); it != propIt->end(); ++it)
        {
            if (!it->name().compare("name"))
            {
                if (it->type() != JSON_STRING)
                    return false;
                crsName = it->as_string();
                return true;
            }
        }
    } else
        return false;
    
    return false;
}
    
// Parse a set of features out of GeoJSON, using libjson
bool VectorParseGeoJSON(ShapeSet &shapes,const std::string &str,std::string &crs)
{
    json_string json = str;
    JSONNode topNode = libjson::parse(json);

    JSONNode crsNode;
    if (!VectorParseTopNode(topNode,shapes,crsNode))
    {
//        NSLog(@"Failed to parse JSON in VectorParseGeoJSON");
        return false;
    }

    std::string crsName;
    if (VectorParseGeoJSONCRS(crsNode,crsName))
    {
        if (!crsName.empty())
            crs = crsName;
    }
    
    return true;
}
    
bool VectorParseGeoJSONAssembly(const std::string &str,std::map<std::string,ShapeSet> &shapes)
{
    json_string json = str;
    
    JSONNode topNode = libjson::parse(json);
    JSONNode crsNode;

    for (JSONNode::iterator nodeIt = topNode.begin();
         nodeIt != topNode.end(); ++nodeIt)
    {
        if (nodeIt->type() == JSON_NODE)
        {
            ShapeSet theseShapes;
            if (VectorParseTopNode(*nodeIt,theseShapes,crsNode))
            {
                json_string name = nodeIt->name();
                std::string nameStr = to_std_string(name);
                shapes[nameStr] = theseShapes;
            } else
                return false;
        }
    }
    
    return true;
}

}
#include <utility>
