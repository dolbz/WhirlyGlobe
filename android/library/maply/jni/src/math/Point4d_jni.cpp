/*  Point4d_jni.cpp
 *  WhirlyGlobeLib
 *
 *  Created by Steve Gifford on 3/21/15.
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

#import <jni.h>
#import "Geometry_jni.h"
#import "WhirlyGlobe.h"

template<> Point4dClassInfo *Point4dClassInfo::classInfoObj = nullptr;

using namespace Eigen;
using namespace WhirlyKit;

extern "C"
JNIEXPORT void JNICALL Java_com_mousebird_maply_Point4d_nativeInit
  (JNIEnv *env, jclass cls)
{
	Point4dClassInfo::getClassInfo(env,cls);
}

// Construct a Java Point4d
JNIEXPORT jobject JNICALL MakePoint4d(JNIEnv *env,const Point4d &pt)
{
	Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo(env,"com/mousebird/maply/Point4d");
	jobject newObj = classInfo->makeWrapperObject(env,nullptr);
	Point4d *inst = classInfo->getObject(env,newObj);
	*inst = pt;
	return newObj;
}

extern "C"
JNIEXPORT void JNICALL Java_com_mousebird_maply_Point4d_initialise
	(JNIEnv *env, jobject obj)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		auto pt = new Point4d(0, 0, 0, 0);
		classInfo->setHandle(env,obj,pt);
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::initialise()");
	}
}

static std::mutex disposeMutex;

extern "C"
JNIEXPORT void JNICALL Java_com_mousebird_maply_Point4d_dispose
	(JNIEnv *env, jobject obj)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		std::lock_guard<std::mutex> lock(disposeMutex);
		Point4d *inst = classInfo->getObject(env,obj);
		delete inst;
		classInfo->clearHandle(env,obj);
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::dispose()");
	}
}

extern "C"
JNIEXPORT jdouble JNICALL Java_com_mousebird_maply_Point4d_getX
	(JNIEnv *env, jobject obj)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		if (Point4d *pt = classInfo->getObject(env,obj))
		{
			return pt->x();
		}
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::getX()");
	}
    return 0.0;
}

extern "C"
JNIEXPORT jdouble JNICALL Java_com_mousebird_maply_Point4d_getY
	(JNIEnv *env, jobject obj)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		if (Point4d *pt = classInfo->getObject(env,obj))
		{
			return pt->y();
		}
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::getY()");
	}
    return 0.0;
}

extern "C"
JNIEXPORT jdouble JNICALL Java_com_mousebird_maply_Point4d_getZ
	(JNIEnv *env, jobject obj)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		if (Point4d *pt = classInfo->getObject(env,obj))
		{
			return pt->z();
		}
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::getZ()");
	}
    return 0.0;
}

extern "C"
JNIEXPORT jdouble JNICALL Java_com_mousebird_maply_Point4d_getW
	(JNIEnv *env, jobject obj)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		if (Point4d *pt = classInfo->getObject(env,obj))
		{
			return pt->w();
		}
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::getW()");
	}
    return 0.0;
}

extern "C"
JNIEXPORT void JNICALL Java_com_mousebird_maply_Point4d_setValue
	(JNIEnv *env, jobject obj, jdouble x, jdouble y, jdouble z, jdouble w)
{
	try
	{
		Point4dClassInfo *classInfo = Point4dClassInfo::getClassInfo();
		if (Point4d *pt = classInfo->getObject(env,obj))
		{
			pt->x() = x;
			pt->y() = y;
			pt->z() = z;
			pt->w() = w;
		}
	}
	catch (...)
	{
		__android_log_print(ANDROID_LOG_ERROR, "Maply", "Crash in Point4d::setValue()");
	}
}
