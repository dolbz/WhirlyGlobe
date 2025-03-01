/*  TextureMTL.mm
 *  WhirlyGlobeLib
 *
 *  Created by Steve Gifford on 5/16/19.
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

#import "TextureMTL.h"
#import "UIImage+Stuff.h"
#import "RawData_NSData.h"
#import <Accelerate/Accelerate.h>
#import "WhirlyKitLog.h"
#import "SceneMTL.h"

namespace WhirlyKit
{

TextureMTL::TextureMTL(const std::string &name)
    : Texture(name), TextureBaseMTL(name), TextureBase(name)
{
}

TextureMTL::TextureMTL(const std::string &name,RawDataRef texData,bool isPVRTC)
    : Texture(name,texData,isPVRTC), TextureBaseMTL(name), TextureBase(name)
{
    if (!texData) {
        NSLog(@"TextureMTL: Got texture with empty data");
    }
    RawNSDataReaderRef texDataRef = std::dynamic_pointer_cast<RawNSDataReader>(texData);
    if (texDataRef && texDataRef->getData() == nil) {
        NSLog(@"TextureMTL: Got texture with empty data");
    }
}
    
TextureMTL::TextureMTL(const std::string &name,UIImage *inImage,int inWidth,int inHeight)
    : Texture(name), TextureBase(name), TextureBaseMTL(name)
{
    NSData *data = [inImage rawDataScaleWidth:inWidth height:inHeight border:0];
    if (!data)
        return;
    width = inWidth;  height = inHeight;
    
    if ((width == 0 || height == 0) && data)
        NSLog(@"TextureMTL: Got textures with 0 width or height");
    
    texData = RawDataRef(new RawNSDataReader(data));
}

TextureMTL::TextureMTL(const std::string &name,UIImage *inImage)
: Texture(name), TextureBase(name), TextureBaseMTL(name)
{
    NSData *data = [inImage rawDataRetWidth:&width height:&height roundUp:true];
    if (!data)
        return;
    
    if ((width == 0 || height == 0) && data)
        NSLog(@"TextureMTL: Got textures with 0 width or height");
    
    texData = RawDataRef(new RawNSDataReader(data));
}

TextureBaseMTL::~TextureBaseMTL()
{
}

#if !TARGET_OS_MACCATALYST
static RawDataRef ConvertRGBA8888toRGB565(const RawDataRef &inData, int width, int height)
{
    vImage_Buffer srcBuff;
    srcBuff.data = (void *)inData->getRawData();
    srcBuff.width = width;
    srcBuff.height = height;
    srcBuff.rowBytes = 4*width;

    NSMutableData *outData = [[NSMutableData alloc] initWithCapacity:width*height*2];
    vImage_Buffer destBuff;
    destBuff.data = (void *)[outData bytes];
    destBuff.width = width;
    destBuff.height = height;
    destBuff.rowBytes = 2*width;
    if (destBuff.data)
    {
        vImageConvert_RGBA8888toRGB565(&srcBuff,&destBuff,kvImageNoFlags);
        return std::make_shared<RawNSDataReader>(outData);
    }
    return RawDataRef();
}
#endif

static RawDataRef ConvertRGBA8888toRGBA5551(const RawDataRef &inData, int width, int height)
{
    vImage_Buffer srcBuff;
    srcBuff.data = (void *)inData->getRawData();
    srcBuff.width = width;
    srcBuff.height = height;
    srcBuff.rowBytes = 4*width;

    NSMutableData *outData = [[NSMutableData alloc] initWithCapacity:width*height*2];
    vImage_Buffer destBuff;
    destBuff.data = (void *)[outData bytes];
    destBuff.width = width;
    destBuff.height = height;
    destBuff.rowBytes = 2*width;
    if (destBuff.data)
    {
        vImageConvert_RGBA8888toRGBA5551(&srcBuff,&destBuff,kvImageNoFlags);
        return std::make_shared<RawNSDataReader>(outData);
    }
    return RawDataRef();
}

RawDataRef TextureMTL::convertData()
{
    switch (format)
    {
    case TexTypeUnsignedByte:
    case TexTypeSingleChannel:
    case TexTypeSingleInt16:
        // no conversion needed?
        return texData;
    case TexTypeDoubleChannel:
        return ConvertRGBATo16(texData,width,height,false);
    case TexTypeShort565:
#if TARGET_OS_MACCATALYST
            // doesn't actually work
            //if (@available(macCatalyst 14.0, *))
            //{
            //    return ConvertRGBA8888toRGB565(texData,width,height);
            //}
            return texData;
#else
            return ConvertRGBA8888toRGB565(texData,width,height);
#endif
    case TexTypeShort4444:
        wkLogLevel(Warn, "TextureMTL: 4444 image format with data not supported on Metal.");
        break;
    case TexTypeShort5551:
#if TARGET_OS_MACCATALYST
            if (@available(macCatalyst 14.0, *))
            {
                return ConvertRGBA8888toRGBA5551(texData,width,height);
            }
            return RawDataRef();
#else
            return ConvertRGBA8888toRGBA5551(texData,width,height);
#endif
    default:
        wkLogLevel(Warn, "TextureMTL: Format %d with data not supported", (int)format);
    }
    return RawDataRef();
}

bool TextureMTL::createInRenderer(const RenderSetupInfo *inSetupInfo)
{
    // already created?
    if (texBuf.tex)
    {
        return true;
    }

    if (!inSetupInfo || (!texData && !isEmptyTexture))
    {
        return false;
    }

    if ((int)width <= 0 || (int)height <= 0)
    {
        if (!isEmptyTexture)
        {
            wkLogLevel(Error,"Texture with 0 width or height: %s",name.c_str());
        }
        return false;
    }
    
    MTLPixelFormat pixFormat;
    int bytesPerRow = 0;
    
    // "Don't use the following pixel formats: r8Unorm_srgb, b5g6r5Unorm, a1bgr5Unorm, abgr4Unorm, bgr5A1Unorm, or any XR10 or YUV formats."
    // https://developer.apple.com/documentation/metal/developing_metal_apps_that_run_in_simulator

    // TODO: Missing all the compressed formats
    switch (format)
    {
        case TexTypeUnsignedByte:
            pixFormat = MTLPixelFormatRGBA8Unorm;
            // Note: Render target.  this is goofy
            if (!texData)
            {
                pixFormat = MTLPixelFormatBGRA8Unorm;
            }
            bytesPerRow = 4*width;
            break;
        case TexTypeShort565:
            // TODO: These aren't the right order
#if TARGET_OS_MACCATALYST
            // Marked as `API_AVAILABLE(macCatalyst(14.0))` but produces "MTLTextureDescriptor has invalid pixelFormat (40)" at runtime
            //if (@available(macCatalyst 14.0, *))
            //{
            //    pixFormat = MTLPixelFormatB5G6R5Unorm;
            //    bytesPerRow = 2*width;
            //}
            //else
            {
                // Requires conversion
                pixFormat = MTLPixelFormatRGBA8Unorm;
                bytesPerRow = 4*width;
            }
#elif TARGET_OS_SIMULATOR
            // Requires conversion
            pixFormat = MTLPixelFormatRGBA8Unorm;
            bytesPerRow = 4*width;
#else
            pixFormat = MTLPixelFormatB5G6R5Unorm;
            bytesPerRow = 2*width;
#endif
            break;
        case TexTypeShort4444:
            // TODO: These aren't the right order
#if TARGET_OS_MACCATALYST
            if (@available(macCatalyst 14.0, *))
            {
                pixFormat = MTLPixelFormatABGR4Unorm;
                bytesPerRow = 2*width;
            }
            else
            {
                pixFormat = MTLPixelFormatRGBA8Unorm;
                bytesPerRow = 4*width;
            }
#elif TARGET_OS_SIMULATOR
            // Requires conversion
            pixFormat = MTLPixelFormatRGBA8Unorm;
            bytesPerRow = 4*width;
#else
            pixFormat = MTLPixelFormatABGR4Unorm;
            bytesPerRow = 2*width;
#endif
            break;
        case TexTypeShort5551:
            // TODO: These aren't the right order
#if TARGET_OS_MACCATALYST
            if (@available(macCatalyst 14.0, *))
            {
                pixFormat = MTLPixelFormatA1BGR5Unorm;
                bytesPerRow = 2*width;
            }
            else
            {
                pixFormat = MTLPixelFormatRGBA8Unorm;
                bytesPerRow = 4*width;
            }
#elif TARGET_OS_SIMULATOR
            // Requires conversion
            pixFormat = MTLPixelFormatRGBA8Unorm;
            bytesPerRow = 4*width;
#else
            pixFormat = MTLPixelFormatA1BGR5Unorm;
            bytesPerRow = 2*width;
#endif
            break;
        case TexTypeSingleChannel:
            pixFormat = MTLPixelFormatA8Unorm;
            // Nudge up the size a bit
            bytesPerRow = width;
            break;
        case TexTypeDoubleChannel:
            pixFormat = MTLPixelFormatRG8Unorm;
            bytesPerRow = 2*width;
            break;
        case TexTypeSingleFloat16:
            pixFormat = MTLPixelFormatR16Float;
            bytesPerRow = 2*width;
            break;
        case TexTypeSingleFloat32:
            pixFormat = MTLPixelFormatR32Float;
            bytesPerRow = 4*width;
            break;
        case TexTypeDoubleFloat16:
            pixFormat = MTLPixelFormatRG16Float;
            bytesPerRow = 4*width;
            break;
        case TexTypeDoubleFloat32:
            pixFormat = MTLPixelFormatRG32Float;
            bytesPerRow = 8*width;
            break;
        case TexTypeQuadFloat16:
            pixFormat = MTLPixelFormatRGBA16Float;
            bytesPerRow = 8*width;
            break;
        case TexTypeQuadFloat32:
            pixFormat = MTLPixelFormatRGBA32Float;
            bytesPerRow = 16*width;
            break;
        case TexTypeDepthFloat32:
            pixFormat = MTLPixelFormatDepth32Float;
            bytesPerRow = 4*width;
            break;
        case TexTypeSingleInt16:
            pixFormat = MTLPixelFormatR16Sint;
            bytesPerRow = 2*width;
            break;
        case TexTypeSingleUInt32:
            pixFormat = MTLPixelFormatR32Uint;
            bytesPerRow = 4*width;
            break;
        case TexTypeDoubleUInt32:
            pixFormat = MTLPixelFormatRG32Uint;
            bytesPerRow = 8*width;
            break;
        case TexTypeQuadUInt32:
            pixFormat = MTLPixelFormatRGBA32Uint;
            bytesPerRow = 16*width;
            break;
        default:
            wkLogLevel(Error,"Unsupported texture format %d", format);
            return false;
    }

    // Set up the texture and upload the data
    MTLTextureDescriptor *desc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:pixFormat
                                                           width:width
                                                          height:height
                                                       mipmapped:usesMipmaps];
    if (!desc)
    {
        wkLogLevel(Error,"Failed to create texture descriptor", format);
        return false;
    }
    
    // If there's no data, then we're using this as a target
    if (!texData)
    {
        desc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;

        // todo: maybe we want private for depth/stencil everywhere?
        // "MTLTextureDescriptor: Depth, Stencil, DepthStencil textures cannot be
        //  allocated with MTLStorageModeShared or MTLStorageModeManaged on this device.
#if TARGET_OS_SIMULATOR
        desc.storageMode = (pixFormat == MTLPixelFormatDepth32Float) ? MTLStorageModePrivate : MTLStorageModeShared;
#elif TARGET_OS_MACCATALYST
        desc.storageMode = (pixFormat == MTLPixelFormatDepth32Float) ? MTLStorageModePrivate : MTLStorageModeManaged;
#endif
    }

    // If there are mipmaps, we probably expect to write to them
    if (usesMipmaps)
    {
        desc.usage |= MTLTextureUsageShaderWrite;
    }

    RenderSetupInfoMTL *setupInfo = (RenderSetupInfoMTL *)inSetupInfo;
    const size_t size = bytesPerRow * height;
    texBuf = setupInfo->heapManage.newTextureWithDescriptor(desc,size);

    if (!name.empty())
    {
        [texBuf.tex setLabel:[NSString stringWithFormat:@"%s",name.c_str()]];
    }

    if (texBuf.tex && texData)
    {
        if (const auto convData = convertData())
        {
            MTLRegion region = MTLRegionMake2D(0,0,width,height);
            [texBuf.tex replaceRegion:region
                          mipmapLevel:0
                            withBytes:convData->getRawData()
                          bytesPerRow:bytesPerRow];
        }
    }

    // We don't need the source data any more
    texData.reset();

    return texBuf.tex != nil;
}

void TextureMTL::destroyInRenderer(const RenderSetupInfo *inSetupInfo,Scene *inScene)
{
    texBuf.tex = nil;
}

}
