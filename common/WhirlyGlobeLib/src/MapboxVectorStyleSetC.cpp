/* MapboxVectorStyleSetC.h
*  WhirlyGlobeLib
*
*  Created by Steve Gifford on 4/8/20.
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

#import "MapboxVectorStyleSetC.h"
#import "MapboxVectorStyleLayer.h"
#import "SharedAttributes.h"
#import "WhirlyKitLog.h"
#import "MapboxVectorStyleBackground.h"
#import <regex>

namespace WhirlyKit
{

#pragma clang diagnostic push
#pragma ide diagnostic ignored "cert-err58-cpp" // NOLINT static initializers can throw
static const std::string strUnderbar("_");
static const std::string strBase("base");
static const std::string strStops("stops");
static const std::string strName("name");
static const std::string strVersion("version");
static const std::string strLayers("layers");
static const std::string strBackground("background");
static const std::regex colorSeparatorPattern("[(),]");
static const std::regex fieldSeparatorPattern(R"([{}]+)");
static const std::regex colonPattern(":\\w+$");
#pragma clang diagnostic pop

bool MapboxRegexField::parse(const std::string &textField)
{
    // Parse out the {} groups in the text
    // TODO: We're missing a boatload of stuff in the spec
    const auto &regex = fieldSeparatorPattern;
    std::sregex_token_iterator it{textField.begin(), textField.end(), regex, -1};
    bool isJustText = textField[0] != '{';
    std::string regexChunk;
    for (; it != std::sregex_token_iterator(); ++it) {
        if (it->length() == 0) {
            continue;
        }
        regexChunk = *it;
        
        MapboxTextChunk textChunk;
        if (isJustText) {
            textChunk.str = std::move(regexChunk);
        } else {
            textChunk.keys.push_back(regexChunk);

            // For some reason name:en is sometimes name_en.
            // Add both, assuming only one will match.
            std::smatch match;
            if (std::regex_search(regexChunk, match, colonPattern)) {
                const auto index = &*match.begin()->first - regexChunk.c_str();
                regexChunk[index] = '_';
                textChunk.keys.emplace_back(std::move(regexChunk));
            }
        }
        chunks.emplace_back(std::move(textChunk));
        isJustText = !isJustText;
    }

    valid = true;

    return true;
}

bool MapboxRegexField::parse(const std::string &fieldName,
                             MapboxVectorStyleSetImpl *,
                             const DictionaryRef &styleEntry)
{
    const std::string textField = MapboxVectorStyleSetImpl::stringValue(fieldName, styleEntry, std::string());
    return textField.empty() || parse(textField);
}

static void trim(std::string &s)
{
    // trim right
    while (!s.empty() && std::isspace(s.back()))
    {
        s.pop_back();
    }
    // trim left
    for (auto i = s.begin(); i != s.end(); ++i)
    {
        if (!std::isspace(*i))
        {
            s.erase(s.begin(), i);
            break;
        }
    }
}

std::string MapboxRegexField::build(const DictionaryRef &attrs) const
{
    bool found = false;
    bool didLookup = false;

    std::string text;
    text.reserve(chunks.size() * 20);

    std::string keyVal;
    for (const auto &chunk : chunks) {
        if (!chunk.str.empty()) {
            text += chunk.str;
            continue;
        }
        for (const auto &key : chunk.keys) {
            didLookup = true;
            if (attrs->hasField(key)) {
                found = true;
                keyVal = attrs->getString(key);
                if (!keyVal.empty()) {
                    text += keyVal;
                    break;
                }
            }
        }
    }

    if (didLookup && !found)
        return std::string();

    trim(text);
    return text;
}

std::string MapboxRegexField::buildDesc(const DictionaryRef &attrs) const
{
    std::string text;
    text.reserve(chunks.size() * 20);

    for (const auto &chunk : chunks) {
        if (!chunk.str.empty()) {
            text += chunk.str;
            continue;
        }
        for (const auto &key : chunk.keys) {
            text += "<";
            text += key;
            text += ">";
        }
    }

    trim(text);
    return text;
}

MaplyVectorFunctionStop::MaplyVectorFunctionStop()
: zoom(-1.0), val(0.0)
{
}

bool MaplyVectorFunctionStops::parse(const DictionaryRef &entry,MapboxVectorStyleSetImpl *,bool isText)
{
    base = entry->getDouble(strBase,1.0);
    
    std::vector<DictionaryEntryRef> dataArray = entry->getArray(strStops);
    if (dataArray.size() < 2)
    {
        wkLogLevel(Warn, "Expecting at least two arguments for function stops.");
        return false;
    }
    for (const auto &stop : dataArray) {
        if (stop->getType() == DictTypeArray) {
            const std::vector<DictionaryEntryRef> stopEntries = stop->getArray();
            if (stopEntries.size() != 2) {
                wkLogLevel(Warn,"Expecting two arguments in each entry for a function stop.");
                return false;
            }

            MaplyVectorFunctionStop fStop;
            fStop.zoom = stopEntries[0]->getDouble();
            if (stopEntries[1]->getType() == DictTypeDouble || stopEntries[1]->getType() == DictTypeInt) {
                fStop.val = stopEntries[1]->getDouble();
            } else {
                switch (stopEntries[1]->getType())
                {
                    case DictTypeString:
                        if (isText)
                            fStop.textField.parse(stopEntries[1]->getString());
                        else
                            fStop.color = MapboxVectorStyleSetImpl::colorValue(std::string(), stopEntries[1],
                                                                               nullptr, nullptr, false);
                        break;
                    case DictTypeObject:
                        fStop.color = std::make_shared<RGBAColor>(stopEntries[1]->getColor());
                        break;
                    default:
                        wkLogLevel(Warn, "Expecting color compatible object in function stop.");
                        return false;
                }
            }
            
            stops.push_back(fStop);
        } else {
            wkLogLevel(Warn, "Expecting arrays in the function stops.");
            return false;
        }
    }

    return true;
}

double MaplyVectorFunctionStops::valueForZoom(double zoom)
{
    const MaplyVectorFunctionStop *a = &stops[0];
    const MaplyVectorFunctionStop *b = nullptr;
    if (zoom <= a->zoom)
        return a->val;
    for (int which = 1;which < stops.size(); which++)
    {
        b = &stops[which];
        if (a->zoom <= zoom && zoom < b->zoom)
        {
            double ratio;
            if (base == 1.0) {
                ratio = (zoom-a->zoom)/(b->zoom-a->zoom);
            } else {
                const double soFar = zoom-a->zoom;
                ratio = (pow(base, soFar) - 1.0) / (pow(base,b->zoom-a->zoom) - 1.0);
            }
            return ratio * (b->val-a->val) + a->val;
        }
        a = b;
    }

    return b ? b->val : 0;
}

RGBAColorRef MaplyVectorFunctionStops::colorForZoom(double zoom)
{
    const MaplyVectorFunctionStop *a = &stops[0];
    const MaplyVectorFunctionStop *b = nullptr;
    if (zoom <= a->zoom)
        return a->color;
    for (int which = 1;which < stops.size(); which++)
    {
        b = &stops[which];
        if (a->zoom <= zoom && zoom < b->zoom)
        {
            double ratio;
            if (base == 1.0) {
                ratio = (zoom-a->zoom)/(b->zoom-a->zoom);
            } else {
                const double soFar = zoom-a->zoom;
                ratio = (pow(base, soFar) - 1.0) / (pow(base,b->zoom-a->zoom) - 1.0);
            }
            float ac[4],bc[4];
            a->color->asUnitFloats(ac);
            b->color->asUnitFloats(bc);
            float res[4];
            for (unsigned int ii=0;ii<4;ii++)
                res[ii] = (float)ratio * (bc[ii]-ac[ii]) + ac[ii];
            return std::make_shared<RGBAColor>(RGBAColor::FromUnitFloats(res));
        }
        a = b;
    }

    return b ? b->color : RGBAColorRef();
}

MapboxRegexField MaplyVectorFunctionStops::textForZoom(double zoom)
{
    const MaplyVectorFunctionStop *a = &stops[0];
    const MaplyVectorFunctionStop *b = nullptr;
    if (zoom <= a->zoom)
        return a->textField;
    for (int which = 1;which < stops.size(); which++)
    {
        b = &stops[which];
        if (a->zoom <= zoom && zoom < b->zoom)
            return a->textField;
        a = b;
    }

    return b ? b->textField : MapboxRegexField();
}

double MaplyVectorFunctionStops::minValue()
{
    double val = MAXFLOAT;

    for (const auto &stop : stops)
    {
        val = std::min(val,stop.val);
    }

    return val;
}

double MaplyVectorFunctionStops::maxValue()
{
    double val = -MAXFLOAT;

    for (const auto &stop : stops)
    {
        val = std::max(val,stop.val);
    }

    return val;
}

MapboxTransDouble::MapboxTransDouble(double value)
{
    val = value;
}

MapboxTransDouble::MapboxTransDouble(MaplyVectorFunctionStopsRef inStops)
{
    val = 0.0;
    stops = std::move(inStops);
}

double MapboxTransDouble::valForZoom(double zoom)
{
    return stops ? stops->valueForZoom(zoom) : val;
}

bool MapboxTransDouble::isExpression()
{
    return stops.get() != nullptr;
}

FloatExpressionInfoRef MapboxTransDouble::expression()
{
    if (!stops)
        return FloatExpressionInfoRef();
    
    auto floatExp = std::make_shared<FloatExpressionInfo>();
    floatExp->type = ExpressionExponential;
    floatExp->base = (float)stops->base;
    floatExp->stopInputs.resize(stops->stops.size());
    floatExp->stopOutputs.resize(stops->stops.size());
    for (size_t ii=0;ii<stops->stops.size();++ii)
    {
        floatExp->stopInputs[ii] = stops->stops[ii].zoom;
        floatExp->stopOutputs[ii] = stops->stops[ii].val;
    }
    
    return floatExp;
}


double MapboxTransDouble::minVal()
{
    return stops ? stops->minValue() : val;
}

double MapboxTransDouble::maxVal()
{
    return stops ? stops->maxValue() : val;
}

MapboxTransColor::MapboxTransColor(RGBAColorRef color) :
    color(std::move(color)),
    useAlphaOverride(false),
    alpha(1.0)
{
}

MapboxTransColor::MapboxTransColor(MaplyVectorFunctionStopsRef stops) :
    useAlphaOverride(false),
    alpha(1.0),
    stops(std::move(stops))
{
}

void MapboxTransColor::setAlphaOverride(double alphaOverride)
{
    useAlphaOverride = true;
    alpha = alphaOverride;
}

RGBAColor MapboxTransColor::colorForZoom(double zoom)
{
    RGBAColor theColor = *(stops ? stops->colorForZoom(zoom) : color);

    if (useAlphaOverride)
    {
        theColor.a = (uint8_t)(alpha * 255.0);
    }
    
    return theColor;
}

bool MapboxTransColor::isExpression() const
{
    return stops.get() != nullptr;
}

ColorExpressionInfoRef MapboxTransColor::expression()
{
    if (!stops)
        return ColorExpressionInfoRef();
    
    auto colorExp = std::make_shared<ColorExpressionInfo>();
    colorExp->type = ExpressionExponential;
    colorExp->base = (float)stops->base;
    colorExp->stopInputs.resize(stops->stops.size());
    colorExp->stopOutputs.resize(stops->stops.size());
    for (unsigned int ii=0;ii<stops->stops.size();ii++) {
        colorExp->stopInputs[ii] = stops->stops[ii].zoom;
        if (stops->stops[ii].color)
            colorExp->stopOutputs[ii] = *(stops->stops[ii].color);
    }
    
    return colorExp;
}

MapboxTransText::MapboxTransText(const std::string &inText)
{
    textField.parse(inText);
}

MapboxTransText::MapboxTransText(MaplyVectorFunctionStopsRef stops) :
    stops(std::move(stops))
{
}

MapboxRegexField MapboxTransText::textForZoom(double zoom)
{
    return stops ? stops->textForZoom(zoom) : textField;
}

static constexpr size_t TypicalLayerCount = 500;

MapboxVectorStyleSetImpl::MapboxVectorStyleSetImpl(Scene *inScene,
                                                   CoordSystem *coordSys,
                                                   VectorStyleSettingsImplRef settings) :
    scene(inScene),
    version(-1),
    currentID(0),
    tileStyleSettings(std::move(settings)),
    coordSys(coordSys),
    zoomSlot(-1),
    layersByName(TypicalLayerCount),
    layersByUUID(TypicalLayerCount),
    layersBySource(TypicalLayerCount)
{
    layers.reserve(TypicalLayerCount);

    vecManage = scene->getManager<VectorManager>(kWKVectorManager);
    wideVecManage = scene->getManager<WideVectorManager>(kWKWideVectorManager);
    markerManage = scene->getManager<MarkerManager>(kWKMarkerManager);
    labelManage = scene->getManager<LabelManager>(kWKLabelManager);
    compManage = scene->getManager<ComponentManager>(kWKComponentManager);

    // We'll look for the versions that do expressions first and
    //  then fall back to the simpler ones
    Program *prog = scene->findProgramByName(MaplyScreenSpaceExpShader);
    if (!prog)
        prog = scene->findProgramByName(MaplyScreenSpaceDefaultShader);
    if (prog)
        screenMarkerProgramID = prog->getId();

    prog = scene->findProgramByName(MaplyTriangleExpShader);
    if (!prog)
        prog = scene->findProgramByName(MaplyDefaultTriangleShader);
    if (prog)
        vectorArealProgramID = prog->getId();

    prog = scene->findProgramByName(MaplyNoLightTriangleExpShader);
    if (!prog)
        prog = scene->findProgramByName(MaplyNoLightTriangleShader);
    if (prog)
        vectorLinearProgramID = prog->getId();

    prog = scene->findProgramByName(MaplyWideVectorExpShader);
    if (!prog)
        prog = scene->findProgramByName(MaplyDefaultWideVectorShader);
    if (prog)
        wideVectorProgramID = prog->getId();
}

bool MapboxVectorStyleSetImpl::parse(PlatformThreadInfo *inst,const DictionaryRef &styleDict)
{
    name = styleDict->getString(strName);
    version = styleDict->getInt(strVersion);

    // Layers are where the action is
    const std::vector<DictionaryEntryRef> layerStyles = styleDict->getArray(strLayers);
    int which = 0;
    for (const auto &layerStyle : layerStyles) {
        if (layerStyle->getType() == DictTypeDictionary) {
            auto layer = MapboxVectorStyleLayer::VectorStyleLayer(inst,this,layerStyle->getDict(),(1*which + tileStyleSettings->baseDrawPriority));
            if (!layer)
            {
                continue;
            }

            // Sort into various buckets for quick lookup
            layersByName[layer->ident] = layer;
            layersByUUID[layer->getUuid(inst)] = layer;
            if (!layer->sourceLayer.empty())
            {
                layersBySource.insert(std::make_pair(layer->sourceLayer, layer));
            }
            layers.push_back(layer);
        }
        which++;
    }
    
    return true;
}

long long MapboxVectorStyleSetImpl::generateID()
{
    return currentID++;
}

int MapboxVectorStyleSetImpl::intValue(const std::string &inName,const DictionaryRef &dict,int defVal)
{
    const auto thing = dict->getEntry(inName);
    switch (thing ? thing->getType() : DictTypeNone)
    {
        case DictTypeDouble:
        case DictTypeInt:
        case DictTypeInt64:
        case DictTypeIdentity:
            return thing->getInt();
        default:
            if (thing)
            {
                wkLogLevel(Warn,"Expected integer for %s but got type %d",inName.c_str(),thing->getType());
            }
            return defVal;
    }
}

double MapboxVectorStyleSetImpl::doubleValue(const DictionaryEntryRef &thing,double defVal)
{
    if (!thing)
        return defVal;

    switch (thing->getType())
    {
        case DictTypeDouble:
        case DictTypeInt:
        case DictTypeIdentity:
        case DictTypeInt64:
            return thing->getDouble();
        default:
            wkLogLevel(Warn, "Expected double but got something else: %s", thing->getString().c_str());
            return defVal;
    }
}

double MapboxVectorStyleSetImpl::doubleValue(const std::string &valName, const DictionaryRef &dict, double defVal)
{
    if (!dict)
        return defVal;

    DictionaryEntryRef thing = dict->getEntry(valName);
    if (!thing)
        return defVal;
    
    if (thing->getType() == DictTypeDouble || thing->getType() == DictTypeInt || thing->getType() == DictTypeIdentity)
        return thing->getDouble();
    
    wkLogLevel(Warn, "Expected double for %s but got something else", valName.c_str());
    return defVal;
}

bool MapboxVectorStyleSetImpl::boolValue(const std::string &valName, const DictionaryRef &dict, const std::string &onString, bool defVal)
{
    if (!dict)
        return defVal;

    const auto thing = dict->getEntry(valName);
    switch (thing ? thing->getType() : DictTypeNone)
    {
        case DictTypeString: return thing->getString() == onString;
        case DictTypeInt:
        case DictTypeInt64:
        case DictTypeIdentity:
        case DictTypeDouble: return thing->getInt() != 0;
        default:             return defVal;
    }
}

std::string MapboxVectorStyleSetImpl::stringValue(const std::string &inName,const DictionaryRef &dict,const std::string &defVal)
{
    if (!dict)
        return defVal;
    
    DictionaryEntryRef thing = dict->getEntry(inName);
    if (!thing)
        return defVal;

    if (thing->getType() == DictTypeString)
        return thing->getString();

    wkLogLevel(Warn, "Expected string for %s but got something else",inName.c_str());
    return defVal;
}

std::vector<DictionaryEntryRef> MapboxVectorStyleSetImpl::arrayValue(const std::string &inName,const DictionaryRef &dict)
{
    std::vector<DictionaryEntryRef> ret;

    if (!dict)
        return ret;
    
    DictionaryEntryRef thing = dict->getEntry(inName);
    if (!thing)
        return ret;
    
    if (thing->getType() == DictTypeArray)
        return thing->getArray();
    
    wkLogLevel(Warn, "Expected array for %s but got something else",inName.c_str());
    return ret;
}

static RGBAColorRef parseColor(const std::string &str, const std::string &inName,
                               const RGBAColorRef &defVal, bool multiplyAlpha)
{
    if (str.empty())
    {
        wkLogLevel(Warn, "Expecting non-empty string for color (%s)", inName.c_str());
        return defVal;
    }
    // Hex string
    if (str[0] == '#')
    {
        // Hex string
        char *end = nullptr;
        uint32_t iVal = ::strtoul(str.c_str() + 1, &end, 16);
        if (end - str.c_str() != str.length())
        {
            // trailing characters not read
            wkLogLevel(Warn, "Invalid hex value '%s' in color '%s'", str.c_str(), inName.c_str());
            return defVal;
        }

        uint8_t red,green,blue;
        uint8_t alpha = 255;
        if (str.size() == 4)            // #RGB => FFRRGGBB
        {
            red = (iVal >> 8) & 0xf;    red |= red << 4;
            green = (iVal >> 4) & 0xf;  green |= green << 4;
            blue = iVal & 0xf;          blue |= blue << 4;
        } else if (str.size() == 5) {   // #RGBA => AARRGGBB
            red = (iVal >> 12) & 0xf;   red |= red << 4;
            green = (iVal >> 8) & 0xf;  green |= green << 4;
            blue = (iVal >> 4) & 0xf;   blue |= blue << 4;
            alpha = iVal & 0xf;         alpha |= alpha << 4;
        } else if (str.size() == 7) {   // #RRGGBB => FFRRGGBB
            red = (iVal >> 16) & 0xff;
            green = (iVal >> 8) & 0xff;
            blue = iVal & 0xff;
        } else if (str.size() == 9) {   // #RRGGBBAA => AARRGGBB
            red = (iVal >> 24) & 0xff;
            green = (iVal >> 16) & 0xff;
            blue = (iVal >> 8) & 0xff;
            alpha = iVal & 0xff;
        } else {    // ?
            wkLogLevel(Warn, "Unrecognized format '%s' in color '%s'", str.c_str(), inName.c_str());
            return defVal;
        }
        if (multiplyAlpha) {
            return std::make_shared<RGBAColor>(RGBAColor(red, green, blue).withAlphaMultiply(alpha / 255.0));
        } else {
            return std::make_shared<RGBAColor>(red, green, blue, alpha);
        }
    } else if (str.find("rgb(") == 0) {
        const auto &reg = colorSeparatorPattern;
        const std::sregex_token_iterator iter(str.begin()+4, str.end(), reg, -1);
        const std::vector<std::string> toks(iter, std::sregex_token_iterator());

        if (toks.size() != 3) {
            wkLogLevel(Warn, "Unrecognized format '%s' in color '%s'", str.c_str(), inName.c_str());
            return defVal;
        }
        const int red = std::stoi(toks[0]);
        const int green = std::stoi(toks[1]);
        const int blue = std::stoi(toks[2]);

        return std::make_shared<RGBAColor>(red,green,blue,255);
    } else if (str.find("rgba(") == 0) {
        const auto &reg = colorSeparatorPattern;
        const std::sregex_token_iterator iter(str.begin()+5, str.end(), reg, -1);
        const std::vector<std::string> toks(iter, std::sregex_token_iterator());

        if (toks.size() != 4) {
            wkLogLevel(Warn, "Unrecognized format '%s' in color '%s'", str.c_str(), inName.c_str());
            return defVal;
        }
        const int red = std::stoi(toks[0]);
        const int green = std::stoi(toks[1]);
        const int blue = std::stoi(toks[2]);
        const double alpha = std::stod(toks[3]);
        
        if (multiplyAlpha) {
            return std::make_shared<RGBAColor>(red * alpha, green * alpha, blue * alpha, 255.0 * alpha);
        } else {
            return std::make_shared<RGBAColor>(red, green, blue, (int) (255.0 * alpha));
        }
    } else if (str.find("hsl(") == 0) {
        const auto &reg = colorSeparatorPattern;
        const std::sregex_token_iterator iter(str.begin()+4, str.end(), reg, -1);
        const std::vector<std::string> toks(iter, std::sregex_token_iterator());

        if (toks.size() != 3) {
            wkLogLevel(Warn, "Unrecognized format '%s' in color '%s'", str.c_str(), inName.c_str());
            return defVal;
        }
        const int hue = std::stoi(toks[0]);
        const int sat = std::stoi(toks[1]);
        const int light = std::stoi(toks[2]);
        const float newLight = (float)light / 100.0f;
        const float newSat = (float)sat / 100.0f;

        return std::make_shared<RGBAColor>(RGBAColor::FromHSL(hue, newSat, newLight));
    } else if (str.find("hsla(") == 0) {
        const auto &reg = colorSeparatorPattern;
        const std::sregex_token_iterator iter(str.begin()+5, str.end(), reg, -1);
        const std::vector<std::string> toks(iter, std::sregex_token_iterator());

        if (toks.size() != 4) {
            wkLogLevel(Warn, "Unrecognized format '%s' in color '%s'", str.c_str(), inName.c_str());
            return defVal;
        }
        const int hue = std::stoi(toks[0]);
        const int sat = std::stoi(toks[1]);
        const int light = std::stoi(toks[2]);
        const auto alpha = (float)std::stod(toks[3]);
        const auto newLight = (float)light / 100.0f;
        const auto newSat = (float)sat / 100.0f;

        const auto c = RGBAColor::FromHSL(hue, newSat, newLight);
        return std::make_shared<RGBAColor>(multiplyAlpha ? c.withAlphaMultiply(alpha) : c.withAlpha(alpha));
    }

    wkLogLevel(Warn, "Unrecognized format '%s' in color '%s'", str.c_str(), inName.c_str());
    return defVal;
}

RGBAColorRef MapboxVectorStyleSetImpl::colorValue(const std::string &inName, const DictionaryEntryRef &val,
                                                  const DictionaryRef &dict, const RGBAColorRef &defVal, bool multiplyAlpha)
{
    const DictionaryEntryRef thing = dict ? dict->getEntry(inName) : val;
    if (!thing)
        return defVal;

    if (thing->getType() != DictTypeString) {
        wkLogLevel(Warn, "Expecting a string for color (%s)", inName.c_str());
        return defVal;
    }

    return parseColor(thing->getString(), inName, defVal, multiplyAlpha);
}

RGBAColorRef MapboxVectorStyleSetImpl::colorValue(const std::string &inName,const DictionaryEntryRef &val,const DictionaryRef &dict,const RGBAColor &defVal,bool multiplyAlpha)
{
    return colorValue(inName, val, dict, std::make_shared<RGBAColor>(defVal), multiplyAlpha);
}

int MapboxVectorStyleSetImpl::enumValue(const DictionaryEntryRef &entry,const char * const options[],int defVal)
{
    if (!entry || entry->getType() != DictTypeString)
        return defVal;

    const std::string localName = entry->getString();
    
    for (int which = 0; options[which]; which++)
    {
        const char * const val = options[which];
        if (!strcmp(val, localName.c_str()))
        {
            return which;
        }
    }

    wkLogLevel(Warn, "Found unexpected value (%s) in enumerated type", localName.c_str());
    return defVal;
}

MapboxTransDoubleRef MapboxVectorStyleSetImpl::transDouble(const DictionaryEntryRef &theEntry, double defVal)
{
    if (!theEntry)
        return std::make_shared<MapboxTransDouble>(defVal);
    
    // This is probably stops
    if (theEntry->getType() == DictTypeDictionary) {
        auto stops = std::make_shared<MaplyVectorFunctionStops>();
        stops->parse(theEntry->getDict(), this, false);
        if (stops) {
            return MapboxTransDoubleRef(new MapboxTransDouble(stops));
        } else {
            wkLogLevel(Warn, "Expecting key word 'stops' in entry %s",name.c_str());
        }
    } else if (theEntry->getType() == DictTypeDouble || theEntry->getType() == DictTypeInt) {
        return std::make_shared<MapboxTransDouble>(theEntry->getDouble());
    } else {
        wkLogLevel(Warn,"Unexpected type found in entry %s. Was expecting a double.",name.c_str());
    }

    return MapboxTransDoubleRef();
}


MapboxTransDoubleRef MapboxVectorStyleSetImpl::transDouble(const std::string &valName, const DictionaryRef &entry, double defVal)
{
    return transDouble(entry ? entry->getEntry(valName) : DictionaryEntryRef(), defVal);
}

MapboxTransColorRef MapboxVectorStyleSetImpl::transColor(const std::string &valName, const DictionaryRef &entry, const RGBAColor *defVal)
{
    const auto defValRef = defVal ? std::make_shared<RGBAColor>(*defVal) : RGBAColorRef();

    if (!entry) {
        return defVal ? std::make_shared<MapboxTransColor>(defValRef) : MapboxTransColorRef();
    }

    // They pass in the whole dictionary and let us look the field up
    const DictionaryEntryRef theEntry = entry->getEntry(valName);
    if (!theEntry) {
        return defVal ? std::make_shared<MapboxTransColor>(defValRef) : MapboxTransColorRef();
    }

    // This is probably stops
    if (theEntry->getType() == DictTypeDictionary) {
        auto stops = std::make_shared<MaplyVectorFunctionStops>();
        if (stops->parse(theEntry->getDict(), this, false)) {
            return std::make_shared<MapboxTransColor>(stops);
        } else {
            wkLogLevel(Warn, "Expecting key word 'stops' in entry %s", valName.c_str());
        }
    } else if (theEntry->getType() == DictTypeString) {
        RGBAColorRef color = colorValue(valName, theEntry, DictionaryRef(), defValRef, false);
        if (color)
            return std::make_shared<MapboxTransColor>(color);
        else {
            wkLogLevel(Warn, "Unexpected type found in entry %s. Was expecting a color.", valName.c_str());
        }
    } else {
        wkLogLevel(Warn, "Unexpected type found in entry %s. Was expecting a color.", valName.c_str());
    }

    return MapboxTransColorRef();
}

MapboxTransColorRef MapboxVectorStyleSetImpl::transColor(const std::string &inName, const DictionaryRef &entry, const RGBAColor &inColor)
{
    const RGBAColor color = inColor;
    return transColor(inName, entry, &color);
}

MapboxTransTextRef MapboxVectorStyleSetImpl::transText(const std::string &inName, const DictionaryRef &entry, const std::string &str)
{
    if (!entry) {
        return str.empty() ? MapboxTransTextRef() : std::make_shared<MapboxTransText>(str);
    }
    
    // They pass in the whole dictionary and let us look the field up
    const DictionaryEntryRef theEntry = entry->getEntry(inName);
    if (!theEntry) {
        return str.empty() ? MapboxTransTextRef() : std::make_shared<MapboxTransText>(str);
    }

    // This is probably stops
    if (theEntry->getType() == DictTypeDictionary) {
        auto stops = std::make_shared<MaplyVectorFunctionStops>();
        if (stops->parse(theEntry->getDict(), this, true)) {
            return std::make_shared<MapboxTransText>(stops);
        } else {
            wkLogLevel(Warn, "Expecting key word 'stops' in entry %s", inName.c_str());
        }
    } else if (theEntry->getType() == DictTypeString) {
        return std::make_shared<MapboxTransText>(theEntry->getString());
    } else {
        wkLogLevel(Warn, "Unexpected type found in entry %s. Was expecting a string.", inName.c_str());
    }

    return MapboxTransTextRef();
}

void MapboxVectorStyleSetImpl::unsupportedCheck(const char *field,const char *what,const DictionaryRef &styleEntry)
{
    if (styleEntry && styleEntry->hasField(field))
    {
#if DEBUG
        wkLogLevel(Warn,"Found unsupported field (%s) for (%s)",field,what);
#endif
    }
}

RGBAColorRef MapboxVectorStyleSetImpl::resolveColor(const MapboxTransColorRef &color,const MapboxTransDoubleRef &opacity,double zoom,MBResolveColorType resolveMode)
{
    // No color means no color
    if (!color)
        return RGBAColorRef();

    const RGBAColor thisColor = color->colorForZoom(zoom);

    // No opacity means full opacity
    if (!opacity || color->hasAlphaOverride())
        return std::make_shared<RGBAColor>(thisColor);

    const float thisOpacity = (float)opacity->valForZoom(zoom) * 255;

    float vals[4];
    thisColor.asUnitFloats(vals);
    switch (resolveMode)
    {
        case MBResolveColorOpacityMultiply:
            return std::make_shared<RGBAColor>(vals[0]*thisOpacity,vals[1]*thisOpacity,vals[2]*thisOpacity,vals[3]*thisOpacity);
        case MBResolveColorOpacityReplaceAlpha:
            return std::make_shared<RGBAColor>(vals[0]*255,vals[1]*255,vals[2]*255,thisOpacity);
        case MBResolveColorOpacityComposeAlpha:
            return std::make_shared<RGBAColor>(vals[0]*255,vals[1]*255,vals[2]*255,vals[3]*thisOpacity);
        default:
            assert(!"Invalid color resolve type");
#ifdef NDEBUG
            return RGBAColorRef();
#endif
    }
}

RGBAColor MapboxVectorStyleSetImpl::color(RGBAColor color,double opacity)
{
    return {
        (uint8_t)(color.r*opacity),
        (uint8_t)(color.g*opacity),
        (uint8_t)(color.b*opacity),
        (uint8_t)(color.a*opacity)
    };
}

MapboxVectorStyleLayerRef MapboxVectorStyleSetImpl::getLayer(const std::string &inName)
{
    const auto it = layersByName.find(inName);
    return (it == layersByName.end()) ? MapboxVectorStyleLayerRef() : it->second;
}

VectorStyleImplRef MapboxVectorStyleSetImpl::backgroundStyle(PlatformThreadInfo *inst) const
{
    const auto it = layersByName.find(strBackground);
    if (it != layersByName.end()) {
        if (auto backLayer = std::dynamic_pointer_cast<MapboxVectorLayerBackground>(it->second)) {
            return backLayer;
        }
    }
    return VectorStyleImplRef();
}

RGBAColorRef MapboxVectorStyleSetImpl::backgroundColor(PlatformThreadInfo *inst,double zoom)
{
    const auto it = layersByName.find(strBackground);
    if (it != layersByName.end()) {
        if (const auto backLayer = std::dynamic_pointer_cast<MapboxVectorLayerBackground>(it->second)) {
            return std::make_shared<RGBAColor>(backLayer->paint.color->colorForZoom(zoom));
        }
    }
    return RGBAColorRef();
}

std::vector<VectorStyleImplRef> MapboxVectorStyleSetImpl::stylesForFeature(PlatformThreadInfo *inst,
                                                                           const Dictionary &attrs,
                                                                           const QuadTreeIdentifier &tileID,
                                                                           const std::string &layerName)
{
    std::vector<VectorStyleImplRef> styles;

    const auto range = layersBySource.equal_range(layerName);
    for (auto i = range.first; i != range.second; ++i)
    {
        auto &layer = i->second;
        if (!layer->filter || layer->filter->testFeature(attrs, tileID))
        {
            if (styles.empty())
            {
                styles.reserve(std::distance(range.first, range.second));
            }
            styles.push_back(layer);
        }
    }
    
    return styles;
}

/// Return true if the given layer is meant to display for the given tile (zoom level)
bool MapboxVectorStyleSetImpl::layerShouldDisplay(PlatformThreadInfo *inst,
                                                  const std::string &layerName,
                                                  const QuadTreeNew::Node &tileID)
{
    const auto range = layersBySource.equal_range(layerName);
    for (auto i = range.first; i != range.second; ++i)
    {
        if (i->second->visible || !i->second->representation.empty())
        {
            return true;
        }
    }
    return false;
}

/// Return the style associated with the given UUID.
VectorStyleImplRef MapboxVectorStyleSetImpl::styleForUUID(PlatformThreadInfo *inst,long long uuid)
{
    const auto it = layersByUUID.find(uuid);
    return (it == layersByUUID.end()) ? nullptr : it->second;
}

// Return a list of all the styles in no particular order.  Needed for categories and indexing
std::vector<VectorStyleImplRef> MapboxVectorStyleSetImpl::allStyles(PlatformThreadInfo *inst)
{
    return std::vector<VectorStyleImplRef>(layers.begin(), layers.end());
}

void MapboxVectorStyleSetImpl::addSprites(MapboxVectorStyleSpritesRef newSprites)
{
    sprites = std::move(newSprites);
}

//#define LOW_LEVEL_UNIT_TESTS
#if defined(LOW_LEVEL_UNIT_TESTS)
static struct UnitTests {
    UnitTests() {
        check(RGBAColor(), RGBAColor(0,0,0,0));
        check(RGBAColor::FromUnitFloats(1.,1.,1.), RGBAColor::white());
        check(RGBAColor::FromUnitFloats(1.,1.,1.,1.), RGBAColor::white());
        check(RGBAColor(0x12,0x34,0x56).withAlphaMultiply(0x78/255.0),
              RGBAColor::FromARGBInt(0x78081828));

        check("", false, RGBAColorRef());
        check("123", false, RGBAColorRef());
        check("#1", false, RGBAColorRef());
        check("#12", false, RGBAColorRef());
        check("#123456789", false, RGBAColorRef());
        check("#abg", false, RGBAColorRef());
        check("red", false, RGBAColorRef());

        check("#123", false, c(0xff112233));
        check("#123", true, c(0xff112233));

        check("#1234", false, c(0x44112233));
        check("#1234", true, c(RGBAColor(0x11,0x22,0x33).
                                        withAlphaMultiply(0x44/255.0)));

        check("#123456", false, c(0xff123456));
        check("#123456", true, c(0xff123456));

        check("#12345678", false, c(0x78123456));
        check("#12345678", true, c(RGBAColor(0x12,0x34,0x56).
                                        withAlphaMultiply(0x78/255.0)));

        check("rgb(1,2,3)", false, c(0xff010203));
        check("rgb(1,2,3)", true, c(0xff010203));

        check("rgba(1,2,3,0.5)", false, c(0x7f010203));
        check("rgba(4,6,8,0.5)", true, c(0x7f020304));

        //todo: hsl/hsla

        wkLog("MapboxStyleSet Color Tests Passed");
    }
    RGBAColorRef c(RGBAColor cv) const { return std::make_shared<RGBAColor>(cv); }
    RGBAColorRef c(uint32_t cv) const { return c(RGBAColor::FromARGBInt(cv)); }
    void check(RGBAColor cv, RGBAColor exp, const char *v = nullptr) {
        check(c(cv),c(exp),v);
    }
    void check(const RGBAColorRef &cv, const RGBAColorRef &exp, const char *v = nullptr) {
        if ((bool)cv != (bool)exp) {
            wkLog("RGBAColor text failed: expected %s got %s%s%s",
                  exp ? "value" : "null", cv ? "value" : "null",
                  v ? " from input: " : "", v ? v : "");
            assert(!"RGBAColor parse test failed");
        }
        if (cv && exp && *cv != *exp) {
            wkLog("RGBAColor parse failed: expected %.8x got %.8x%s%s",
                  exp->asARGBInt(), cv->asARGBInt(),
                  v ? " from input: " : "", v ? v : "");
            assert(!"RGBAColor parse test failed");
        }
    }
    void check(const std::string& s, bool multiply, const RGBAColorRef &exp) {
        const auto c = parseColor(s, "s", RGBAColorRef(), multiply);
        check(c, exp, s.c_str());
    }
} tests;
#endif

}
