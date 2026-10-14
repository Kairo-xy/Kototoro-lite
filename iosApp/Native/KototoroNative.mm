// Kototoro's JNI implementation of the extension platform operations, using Apple's public frameworks.
#if KTR_HAS_JVM
#include <jni.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <CoreText/CoreText.h>
#import <JavaScriptCore/JavaScriptCore.h>
#import <CommonCrypto/CommonDigest.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
NSString *string(JNIEnv *env, jstring value) {
    if (value == nullptr) return @"";
    const jchar *characters = env->GetStringChars(value, nullptr);
    if (characters == nullptr) return nil;
    NSString *result = [[NSString alloc] initWithCharacters:characters length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, characters);
    return result;
}
jstring javaString(JNIEnv *env, NSString *value) {
    if (value == nil) return nullptr;
    std::vector<jchar> characters(value.length);
    [value getCharacters:characters.data() range:NSMakeRange(0, value.length)];
    return env->NewString(characters.data(), static_cast<jsize>(characters.size()));
}
void fail(JNIEnv *env, const char *type, const char *message) {
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}
NSData *bytes(JNIEnv *env, jbyteArray value) {
    if (value == nullptr) return nil;
    jsize length = env->GetArrayLength(value);
    NSMutableData *result = [NSMutableData dataWithLength:static_cast<NSUInteger>(length)];
    env->GetByteArrayRegion(value, 0, length, static_cast<jbyte *>(result.mutableBytes));
    return env->ExceptionCheck() ? nil : result;
}
jintArray integers(JNIEnv *env, const std::vector<jint> &values) {
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}
// CoreGraphics uses premultiplied RGBA; Android's public getPixels/setPixels use unpremultiplied ARGB.
std::vector<uint8_t> rgba(const std::vector<jint> &pixels) {
    std::vector<uint8_t> result(pixels.size() * 4);
    for (size_t i = 0; i < pixels.size(); ++i) {
        uint32_t p = static_cast<uint32_t>(pixels[i]); unsigned a = p >> 24;
        result[i * 4] = (((p >> 16) & 255) * a + 127) / 255;
        result[i * 4 + 1] = (((p >> 8) & 255) * a + 127) / 255;
        result[i * 4 + 2] = ((p & 255) * a + 127) / 255;
        result[i * 4 + 3] = a;
    }
    return result;
}
std::vector<jint> argb(const std::vector<uint8_t> &pixels) {
    std::vector<jint> result(pixels.size() / 4);
    for (size_t i = 0; i < result.size(); ++i) {
        unsigned a = pixels[i * 4 + 3]; uint32_t p = a << 24;
        if (a != 0) for (unsigned component = 0; component < 3; ++component)
            p |= std::min(255u, (pixels[i * 4 + component] * 255u + a / 2) / a) << (16 - component * 8);
        result[i] = static_cast<jint>(p);
    }
    return result;
}
CGContextRef bitmapContext(std::vector<uint8_t> &pixels, size_t width, size_t height) {
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(pixels.data(), width, height, 8, width * 4, space,
        kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(space);
    return context;
}
bool dimensions(JNIEnv *env, jintArray pixels, jint width, jint height) {
    if (pixels == nullptr || width <= 0 || height <= 0 || static_cast<int64_t>(width) * height > 64 * 1024 * 1024 ||
        env->GetArrayLength(pixels) != static_cast<int64_t>(width) * height) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid pixel buffer dimensions"); return false;
    }
    return true;
}
jintArray decodeImage(JNIEnv *env, jclass, jbyteArray encoded, jboolean bounds, jint sample) {
    @autoreleasepool {
        NSData *data = bytes(env, encoded);
        if (data == nil || sample < 1) return nullptr;
        CGImageSourceRef source = CGImageSourceCreateWithData((__bridge CFDataRef)data, nullptr);
        if (source == nullptr) return nullptr;
        NSDictionary *properties = CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(source, 0, nullptr));
        size_t width = [properties[(__bridge NSString *)kCGImagePropertyPixelWidth] unsignedLongValue];
        size_t height = [properties[(__bridge NSString *)kCGImagePropertyPixelHeight] unsignedLongValue];
        if (width == 0 || height == 0 || width > INT_MAX || height > INT_MAX) { CFRelease(source); return nullptr; }
        if (bounds) { CFRelease(source); return integers(env, {static_cast<jint>(width), static_cast<jint>(height)}); }
        width = std::max<size_t>(1, width / sample); height = std::max<size_t>(1, height / sample);
        if (width * height > 64 * 1024 * 1024) { CFRelease(source); return nullptr; }
        NSDictionary *options = @{(__bridge NSString *)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
            (__bridge NSString *)kCGImageSourceThumbnailMaxPixelSize: @(std::max(width, height)),
            (__bridge NSString *)kCGImageSourceShouldCacheImmediately: @YES};
        CGImageRef image = sample == 1 ? CGImageSourceCreateImageAtIndex(source, 0, nullptr) :
            CGImageSourceCreateThumbnailAtIndex(source, 0, (__bridge CFDictionaryRef)options);
        CFRelease(source);
        if (image == nullptr) return nullptr;
        std::vector<uint8_t> pixels(width * height * 4);
        CGContextRef context = bitmapContext(pixels, width, height);
        if (context == nullptr) { CGImageRelease(image); return nullptr; }
        CGContextDrawImage(context, CGRectMake(0, 0, width, height), image);
        CGContextRelease(context); CGImageRelease(image);
        auto result = argb(pixels);
        result.insert(result.begin(), {static_cast<jint>(width), static_cast<jint>(height)});
        return integers(env, result);
    }
}
jbyteArray encodeImage(JNIEnv *env, jclass, jintArray input, jint width, jint height, jstring format, jint quality) {
    @autoreleasepool {
        if (!dimensions(env, input, width, height)) return nullptr;
        NSString *name = string(env, format);
        CFStringRef type = [name isEqualToString:@"PNG"] ? CFSTR("public.png") :
            [name isEqualToString:@"JPEG"] ? CFSTR("public.jpeg") : CFSTR("org.webmproject.webp");
        // Report unsupported encoders as failure; never produce PNG bytes labelled as WebP.
        NSArray *types = CFBridgingRelease(CGImageDestinationCopyTypeIdentifiers());
        if (![types containsObject:(__bridge NSString *)type]) return nullptr;
        std::vector<jint> values(width * height);
        env->GetIntArrayRegion(input, 0, width * height, values.data());
        if (env->ExceptionCheck()) return nullptr;
        auto pixels = rgba(values);
        CGContextRef context = bitmapContext(pixels, width, height);
        if (context == nullptr) return nullptr;
        CGImageRef image = CGBitmapContextCreateImage(context);
        CGContextRelease(context);
        NSMutableData *output = [NSMutableData data];
        CGImageDestinationRef destination = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)output, type, 1, nullptr);
        if (destination == nullptr) { CGImageRelease(image); return nullptr; }
        NSDictionary *options = @{(__bridge NSString *)kCGImageDestinationLossyCompressionQuality: @(quality / 100.0)};
        CGImageDestinationAddImage(destination, image, (__bridge CFDictionaryRef)options);
        bool success = CGImageDestinationFinalize(destination);
        CFRelease(destination); CGImageRelease(image);
        if (!success || output.length > INT_MAX) return nullptr;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.length));
        if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.length), static_cast<const jbyte *>(output.bytes));
        return result;
    }
}
CTFontRef font(JNIEnv *env, jstring family, jint style, jfloat size) {
    CTFontRef basic = CTFontCreateWithName((__bridge CFStringRef)string(env, family), size, nullptr);
    CTFontSymbolicTraits traits = ((style & 1) ? kCTFontBoldTrait : 0) | ((style & 2) ? kCTFontItalicTrait : 0);
    CTFontRef styled = traits == 0 ? nullptr : CTFontCreateCopyWithSymbolicTraits(basic, size, nullptr, traits, traits);
    if (styled != nullptr) { CFRelease(basic); return styled; }
    return basic;
}
CTLineRef textLine(JNIEnv *env, jstring text, CTFontRef typeface, CGColorRef color) {
    NSMutableDictionary *attributes = [NSMutableDictionary dictionaryWithObject:(__bridge id)typeface forKey:(__bridge NSString *)kCTFontAttributeName];
    if (color != nullptr) attributes[(__bridge NSString *)kCTForegroundColorAttributeName] = (__bridge id)color;
    NSAttributedString *value = [[NSAttributedString alloc] initWithString:string(env, text) attributes:attributes];
    return CTLineCreateWithAttributedString((__bridge CFAttributedStringRef)value);
}
jfloatArray measureText(JNIEnv *env, jclass, jstring text, jstring family, jint style, jfloat size) {
    @autoreleasepool {
        CTFontRef typeface = font(env, family, style, size);
        CTLineRef line = textLine(env, text, typeface, nullptr);
        jfloat values[] = {static_cast<jfloat>(CTLineGetTypographicBounds(line, nullptr, nullptr, nullptr)),
            static_cast<jfloat>(CTFontGetAscent(typeface)), static_cast<jfloat>(CTFontGetDescent(typeface)), static_cast<jfloat>(CTFontGetLeading(typeface))};
        CFRelease(line); CFRelease(typeface);
        jfloatArray result = env->NewFloatArray(4);
        if (result != nullptr) env->SetFloatArrayRegion(result, 0, 4, values);
        return result;
    }
}
void drawText(JNIEnv *env, jclass, jintArray input, jint width, jint height, jstring text, jfloat x, jfloat y,
              jstring family, jint style, jfloat size, jint color, jfloatArray transform) {
    @autoreleasepool {
        if (!dimensions(env, input, width, height)) return;
        std::vector<jint> values(width * height); env->GetIntArrayRegion(input, 0, width * height, values.data());
        if (env->ExceptionCheck()) return;
        auto pixels = rgba(values);
        CGContextRef context = bitmapContext(pixels, width, height);
        if (context == nullptr) return;
        if (transform == nullptr || env->GetArrayLength(transform) != 6) {
            CGContextRelease(context); fail(env, "java/lang/IllegalArgumentException", "Invalid text transform"); return;
        }
        jfloat matrix[6]; env->GetFloatArrayRegion(transform, 0, 6, matrix);
        // Convert Android's top-left affine transform into CoreText's bottom-left coordinate system.
        CGContextConcatCTM(context, CGAffineTransformMake(matrix[0], -matrix[1], -matrix[2], matrix[3],
            matrix[2] * height + matrix[4], height * (1 - matrix[3]) - matrix[5]));
        CTFontRef typeface = font(env, family, style, size);
        CGColorRef ink = CGColorCreateSRGB(((color >> 16) & 255) / 255.0, ((color >> 8) & 255) / 255.0,
            (color & 255) / 255.0, (static_cast<uint32_t>(color) >> 24) / 255.0);
        CTLineRef line = textLine(env, text, typeface, ink);
        CGContextSetTextMatrix(context, CGAffineTransformIdentity);
        CGContextSetTextPosition(context, x, height - y);
        CTLineDraw(line, context);
        CFRelease(line); CFRelease(ink); CFRelease(typeface); CGContextRelease(context);
        values = argb(pixels); env->SetIntArrayRegion(input, 0, width * height, values.data());
    }
}

std::mutex handlesMutex;
jlong nextHandle = 1;
using Pdf = std::shared_ptr<CGPDFDocument>;
std::unordered_map<jlong, Pdf> documents;
Pdf pdf(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = documents.find(handle);
    if (found == documents.end()) { fail(env, "java/lang/IllegalStateException", "PDF handle is closed"); return nullptr; }
    return found->second;
}
jlong openPdf(JNIEnv *env, jclass, jbyteArray encoded) {
    @autoreleasepool {
        NSData *data = bytes(env, encoded);
        if (data == nil) return 0;
        CGDataProviderRef provider = CGDataProviderCreateWithCFData((__bridge CFDataRef)data);
        CGPDFDocumentRef document = CGPDFDocumentCreateWithProvider(provider);
        CGDataProviderRelease(provider);
        if (document == nullptr) return 0;
        if (!CGPDFDocumentIsUnlocked(document)) { CGPDFDocumentRelease(document); return 0; }
        std::lock_guard<std::mutex> lock(handlesMutex);
        jlong handle = nextHandle++;
        documents.emplace(handle, Pdf(document, CGPDFDocumentRelease));
        return handle;
    }
}
jintArray pdfPageInfo(JNIEnv *env, jclass, jlong handle, jint index) {
    auto document = pdf(env, handle); if (!document) return nullptr;
    if (index == -1) return integers(env, {static_cast<jint>(CGPDFDocumentGetNumberOfPages(document.get()))});
    CGPDFPageRef page = CGPDFDocumentGetPage(document.get(), index + 1);
    if (page == nullptr) { fail(env, "java/lang/IllegalArgumentException", "Invalid PDF page index"); return nullptr; }
    CGRect bounds = CGPDFPageGetBoxRect(page, kCGPDFMediaBox);
    int rotation = CGPDFPageGetRotationAngle(page);
    if (rotation % 180 != 0) std::swap(bounds.size.width, bounds.size.height);
    return integers(env, {static_cast<jint>(std::ceil(bounds.size.width)), static_cast<jint>(std::ceil(bounds.size.height))});
}
void renderPdf(JNIEnv *env, jclass, jlong handle, jint index, jintArray input, jint width, jint height) {
    auto document = pdf(env, handle); if (!document || !dimensions(env, input, width, height)) return;
    CGPDFPageRef page = CGPDFDocumentGetPage(document.get(), index + 1);
    if (page == nullptr) { fail(env, "java/lang/IllegalArgumentException", "Invalid PDF page index"); return; }
    std::vector<uint8_t> pixels(width * height * 4, 255);
    CGContextRef context = bitmapContext(pixels, width, height); if (context == nullptr) return;
    CGContextConcatCTM(context, CGPDFPageGetDrawingTransform(page, kCGPDFMediaBox, CGRectMake(0, 0, width, height), 0, true));
    CGContextDrawPDFPage(context, page); CGContextRelease(context);
    auto values = argb(pixels); env->SetIntArrayRegion(input, 0, width * height, values.data());
}
void closePdf(JNIEnv *, jclass, jlong handle) { std::lock_guard<std::mutex> lock(handlesMutex); documents.erase(handle); }

struct Script {
    JSGlobalContextRef context = nullptr;
    JavaVM *vm = nullptr;
    jobject bridge = nullptr;
    jmethodID invoke = nullptr;
    std::recursive_mutex mutex;
    bool closed = false;
    ~Script() { if (context != nullptr) JSGlobalContextRelease(context); }
};
std::unordered_map<jlong, std::shared_ptr<Script>> scripts;
JSStringRef jsString(NSString *text) {
    std::vector<unichar> chars(text.length); [text getCharacters:chars.data() range:NSMakeRange(0, text.length)];
    return JSStringCreateWithCharacters(chars.data(), chars.size());
}
NSString *jsText(JSContextRef context, JSValueRef value) {
    JSStringRef text = JSValueToStringCopy(context, value, nullptr);
    if (text == nullptr) return @"";
    NSString *result = [[NSString alloc] initWithCharacters:JSStringGetCharactersPtr(text) length:JSStringGetLength(text)];
    JSStringRelease(text); return result;
}
JSValueRef invokeJava(JSContextRef context, JSObjectRef function, JSObjectRef, size_t count,
                      const JSValueRef values[], JSValueRef *exception) {
    @autoreleasepool {
        Script *script = static_cast<Script *>(JSObjectGetPrivate(function));
        JNIEnv *env = nullptr;
        if (script == nullptr || script->closed || count != 3 || script->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK) {
            JSStringRef message = JSStringCreateWithUTF8CString("JavaScript binding is unavailable");
            *exception = JSValueMakeString(context, message); JSStringRelease(message); return JSValueMakeUndefined(context);
        }
        env->PushLocalFrame(16);
        jstring name = javaString(env, jsText(context, values[0])), method = javaString(env, jsText(context, values[1])), args = javaString(env, jsText(context, values[2]));
        jstring output = static_cast<jstring>(env->CallObjectMethod(script->bridge, script->invoke, name, method, args));
        if (env->ExceptionCheck()) {
            jthrowable thrown = env->ExceptionOccurred(); env->ExceptionClear();
            jclass type = env->GetObjectClass(thrown);
            jmethodID describe = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
            jstring description = static_cast<jstring>(env->CallObjectMethod(thrown, describe));
            JSStringRef message = jsString(string(env, description));
            *exception = JSValueMakeString(context, message); JSStringRelease(message);
            env->ExceptionClear(); env->PopLocalFrame(nullptr); return JSValueMakeUndefined(context);
        }
        JSStringRef result = jsString(string(env, output));
        JSValueRef value = JSValueMakeString(context, result); JSStringRelease(result);
        env->PopLocalFrame(nullptr); return value;
    }
}
jlong createJavaScript(JNIEnv *env, jclass, jobject bridge) {
    auto script = std::make_shared<Script>();
    env->GetJavaVM(&script->vm);
    script->bridge = env->NewGlobalRef(bridge);
    jclass type = env->GetObjectClass(bridge);
    script->invoke = env->GetMethodID(type, "invokeBridge", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (script->invoke == nullptr || script->bridge == nullptr) { if (script->bridge != nullptr) env->DeleteGlobalRef(script->bridge); return 0; }
    script->context = JSGlobalContextCreate(nullptr);
    JSClassDefinition definition = kJSClassDefinitionEmpty;
    definition.callAsFunction = invokeJava;
    JSClassRef binding = JSClassCreate(&definition);
    JSObjectRef callback = JSObjectMake(script->context, binding, script.get()); JSClassRelease(binding);
    JSStringRef name = JSStringCreateWithUTF8CString("__kototoroInvoke");
    JSObjectSetProperty(script->context, JSContextGetGlobalObject(script->context), name, callback, kJSPropertyAttributeReadOnly | kJSPropertyAttributeDontDelete, nullptr);
    JSStringRelease(name);
    std::lock_guard<std::mutex> lock(handlesMutex); jlong handle = nextHandle++; scripts.emplace(handle, script); return handle;
}
std::shared_ptr<Script> scriptHandle(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(handlesMutex);
    auto found = scripts.find(handle);
    if (found == scripts.end()) { fail(env, "java/lang/IllegalStateException", "JavaScript context is closed"); return nullptr; }
    return found->second;
}
jstring evaluateJavaScript(JNIEnv *env, jclass, jlong handle, jstring input, jstring filename) {
    @autoreleasepool {
        auto script = scriptHandle(env, handle); if (!script) return nullptr;
        std::lock_guard<std::recursive_mutex> lock(script->mutex);
        if (script->closed) { fail(env, "java/lang/IllegalStateException", "JavaScript context is closed"); return nullptr; }
        JSStringRef code = jsString(string(env, input)), file = jsString(string(env, filename));
        JSValueRef exception = nullptr;
        JSValueRef value = JSEvaluateScript(script->context, code, nullptr, file, 1, &exception);
        JSStringRelease(code); JSStringRelease(file);
        if (exception != nullptr) {
            // Throw with a UTF-16 Java message (ThrowNew's modified UTF-8 would corrupt non-ASCII diagnostics).
            jclass type = env->FindClass("app/cash/quickjs/QuickJsException");
            jmethodID constructor = type == nullptr ? nullptr : env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
            if (constructor != nullptr) env->Throw(static_cast<jthrowable>(env->NewObject(type, constructor, javaString(env, jsText(script->context, exception)))));
            return nullptr;
        }
        if (value == nullptr || JSValueIsUndefined(script->context, value)) return env->NewStringUTF("null");
        JSStringRef json = JSValueCreateJSONString(script->context, value, 0, &exception);
        if (json == nullptr) { fail(env, "app/cash/quickjs/QuickJsException", "Result cannot be represented as JSON"); return nullptr; }
        NSString *text = [[NSString alloc] initWithCharacters:JSStringGetCharactersPtr(json) length:JSStringGetLength(json)];
        JSStringRelease(json); return javaString(env, text);
    }
}
void closeJavaScript(JNIEnv *env, jclass, jlong handle) {
    auto script = scriptHandle(env, handle); if (!script) return;
    std::lock_guard<std::recursive_mutex> lock(script->mutex);
    if (script->closed) return;
    script->closed = true; env->DeleteGlobalRef(script->bridge); script->bridge = nullptr;
    std::lock_guard<std::mutex> registry(handlesMutex); scripts.erase(handle);
}
}

// HTTP transport for OkHttp's network stage (org.skepsun.kototoro.ios.shims.NativeTransport): URLSession does
// connections, TLS and decompression natively; OkHttp keeps redirects, cookies, caching and every interceptor.
// One state per request; its condition guards the head, the unread body and completion.
@interface KTRNetState : NSObject
@property(nonatomic, strong) NSCondition *condition;
@property(nonatomic, strong) NSURLSessionDataTask *task;
@property(nonatomic, strong) NSArray<NSString *> *head;
@property(nonatomic, strong) NSMutableData *body;
@property(nonatomic) NSUInteger offset;
@property(nonatomic) BOOL done;
@property(nonatomic, strong) NSError *error;
@end
@implementation KTRNetState
@end

@interface KTRNetDelegate : NSObject <NSURLSessionDataDelegate>
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, KTRNetState *> *states;
@end
@implementation KTRNetDelegate
- (KTRNetState *)stateFor:(NSURLSessionTask *)task {
    @synchronized(self.states) { return self.states[@(task.taskIdentifier)]; }
}
// OkHttp follows redirects itself, so cookies and interceptors see every hop.
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task
    willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request
    completionHandler:(void (^)(NSURLRequest *))completionHandler {
    completionHandler(nil);
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveResponse:(NSURLResponse *)response
    completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
    KTRNetState *state = [self stateFor:task];
    NSArray<NSString *> *head = [KTRNetDelegate headFor:response];
    [state.condition lock]; state.head = head; [state.condition broadcast]; [state.condition unlock];
    completionHandler(NSURLSessionResponseAllow);
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data {
    KTRNetState *state = [self stateFor:task];
    [state.condition lock]; [state.body appendData:data]; [state.condition broadcast]; [state.condition unlock];
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    KTRNetState *state = [self stateFor:task];
    @synchronized(self.states) { [self.states removeObjectForKey:@(task.taskIdentifier)]; }
    [state.condition lock]; state.done = YES; state.error = error; [state.condition broadcast]; [state.condition unlock];
}
/// [code, message, name, value, ...]. URLSession already decoded gzip/deflate/br bodies, so their encoding and
/// length must not reach OkHttp's transparent gzip; it merges repeated Set-Cookie fields, so those are split again.
+ (NSArray<NSString *> *)headFor:(NSURLResponse *)response {
    NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
    NSMutableArray<NSString *> *head = [NSMutableArray arrayWithObjects:@(http.statusCode).stringValue, @"", nil];
    NSString *encoding = [[http valueForHTTPHeaderField:@"Content-Encoding"] lowercaseString] ?: @"";
    BOOL decoded = encoding.length > 0;
    for (NSString *part in [encoding componentsSeparatedByString:@","]) {
        NSString *coding = [part stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
        if (![@[@"gzip", @"x-gzip", @"deflate", @"br"] containsObject:coding]) decoded = NO;
    }
    [http.allHeaderFields enumerateKeysAndObjectsUsingBlock:^(id key, id value, BOOL *) {
        NSString *name = [key description], *lower = name.lowercaseString;
        if ([lower isEqualToString:@"set-cookie"]) return;
        if (decoded && ([lower isEqualToString:@"content-encoding"] || [lower isEqualToString:@"content-length"])) return;
        [head addObject:name]; [head addObject:[value description]];
    }];
    NSString *cookies = [http valueForHTTPHeaderField:@"Set-Cookie"];
    if (cookies.length > 0 && http.URL != nil) {
        for (NSHTTPCookie *cookie in [NSHTTPCookie cookiesWithResponseHeaderFields:@{@"Set-Cookie": cookies} forURL:http.URL]) {
            [head addObject:@"Set-Cookie"]; [head addObject:[KTRNetDelegate setCookie:cookie]];
        }
    }
    return head;
}
+ (NSString *)setCookie:(NSHTTPCookie *)cookie {
    NSMutableString *line = [NSMutableString stringWithFormat:@"%@=%@; Path=%@", cookie.name, cookie.value,
                             cookie.path.length > 0 ? cookie.path : @"/"];
    // NSHTTPCookie marks a Domain attribute with a leading dot; without it the cookie is host-only.
    if ([cookie.domain hasPrefix:@"."]) [line appendFormat:@"; Domain=%@", [cookie.domain substringFromIndex:1]];
    if (cookie.expiresDate != nil) {
        static NSDateFormatter *format;
        static dispatch_once_t once;
        dispatch_once(&once, ^{
            format = [NSDateFormatter new];
            format.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
            format.timeZone = [NSTimeZone timeZoneForSecondsFromGMT:0];
            format.dateFormat = @"EEE, dd MMM yyyy HH:mm:ss 'GMT'";
        });
        [line appendFormat:@"; Expires=%@", [format stringFromDate:cookie.expiresDate]];
    }
    if (cookie.isSecure) [line appendString:@"; Secure"];
    if (cookie.isHTTPOnly) [line appendString:@"; HttpOnly"];
    if (cookie.sameSitePolicy.length > 0) [line appendFormat:@"; SameSite=%@", cookie.sameSitePolicy];
    return line;
}
@end

namespace {
std::mutex netMutex;
std::unordered_map<jlong, KTRNetState *> netStates;
jlong nextNet = 1;
KTRNetDelegate *netDelegate;
NSURLSession *netSession() {
    static NSURLSession *session;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration defaultSessionConfiguration];
        // OkHttp's BridgeInterceptor owns cookies, CacheInterceptor owns caching.
        configuration.HTTPCookieStorage = nil;
        configuration.HTTPShouldSetCookies = NO;
        configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever;
        configuration.URLCache = nil;
        configuration.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
        configuration.timeoutIntervalForResource = 24 * 60 * 60;
        netDelegate = [KTRNetDelegate new];
        netDelegate.states = [NSMutableDictionary new];
        NSOperationQueue *queue = [NSOperationQueue new];
        queue.maxConcurrentOperationCount = 1;
        queue.name = @"Kototoro native HTTP";
        session = [NSURLSession sessionWithConfiguration:configuration delegate:netDelegate delegateQueue:queue];
    });
    return session;
}
KTRNetState *netState(JNIEnv *env, jlong handle) {
    std::lock_guard<std::mutex> lock(netMutex);
    auto found = netStates.find(handle);
    if (found == netStates.end()) { fail(env, "java/io/IOException", "Native request is closed"); return nil; }
    return found->second;
}
void netFail(JNIEnv *env, NSError *error) {
    if (error.code == NSURLErrorTimedOut) { fail(env, "java/net/SocketTimeoutException", "timeout"); return; }
    const char *type = error.code == NSURLErrorCannotFindHost || error.code == NSURLErrorDNSLookupFailed
        ? "java/net/UnknownHostException" : "java/io/IOException";
    jclass exception = env->FindClass(type);
    if (exception == nullptr) return;
    jmethodID constructor = env->GetMethodID(exception, "<init>", "(Ljava/lang/String;)V");
    NSString *message = [NSString stringWithFormat:@"%@ (%@ %ld)", error.localizedDescription, error.domain, (long)error.code];
    if (constructor != nullptr) env->Throw(static_cast<jthrowable>(env->NewObject(exception, constructor, javaString(env, message))));
}
jboolean netAvailable(JNIEnv *, jclass) { return JNI_TRUE; }
jlong netStart(JNIEnv *env, jclass, jstring address, jstring method, jobjectArray headers, jbyteArray body, jint timeout) {
    @autoreleasepool {
        NSURL *url = [NSURL URLWithString:string(env, address)];
        if (url == nil) { fail(env, "java/io/IOException", "Invalid URL"); return 0; }
        NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
        request.HTTPMethod = string(env, method);
        request.HTTPShouldHandleCookies = NO;
        request.timeoutInterval = timeout > 0 ? timeout / 1000.0 : 24 * 60 * 60;
        // URLSession derives framing and connection headers itself.
        NSSet *reserved = [NSSet setWithObjects:@"host", @"connection", @"content-length", @"transfer-encoding",
                           @"keep-alive", @"proxy-connection", @"upgrade", nil];
        jsize count = headers == nullptr ? 0 : env->GetArrayLength(headers);
        for (jsize i = 0; i + 1 < count; i += 2) {
            auto name = static_cast<jstring>(env->GetObjectArrayElement(headers, i));
            auto value = static_cast<jstring>(env->GetObjectArrayElement(headers, i + 1));
            NSString *field = string(env, name);
            if (![reserved containsObject:field.lowercaseString]) [request addValue:string(env, value) forHTTPHeaderField:field];
            env->DeleteLocalRef(name); env->DeleteLocalRef(value);
        }
        if (body != nullptr) request.HTTPBody = bytes(env, body);
        if (env->ExceptionCheck()) return 0;
        KTRNetState *state = [KTRNetState new];
        state.condition = [NSCondition new];
        state.body = [NSMutableData new];
        NSURLSessionDataTask *task = [netSession() dataTaskWithRequest:request];
        state.task = task;
        @synchronized(netDelegate.states) { netDelegate.states[@(task.taskIdentifier)] = state; }
        jlong handle;
        { std::lock_guard<std::mutex> lock(netMutex); handle = nextNet++; netStates.emplace(handle, state); }
        [task resume];
        return handle;
    }
}
/// The response head, or null while it has not arrived within [wait] milliseconds.
jobjectArray netHead(JNIEnv *env, jclass, jlong handle, jint wait) {
    @autoreleasepool {
        KTRNetState *state = netState(env, handle); if (state == nil) return nullptr;
        NSArray<NSString *> *head; NSError *error; BOOL done;
        [state.condition lock];
        if (state.head == nil && !state.done) [state.condition waitUntilDate:[NSDate dateWithTimeIntervalSinceNow:wait / 1000.0]];
        head = state.head; error = state.error; done = state.done;
        [state.condition unlock];
        if (head == nil) {
            if (error != nil) netFail(env, error);
            else if (done) fail(env, "java/io/IOException", "Response without headers");
            return nullptr;
        }
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(head.count), env->FindClass("java/lang/String"), nullptr);
        for (NSUInteger i = 0; result != nullptr && i < head.count; ++i) {
            jstring value = javaString(env, head[i]);
            env->SetObjectArrayElement(result, static_cast<jsize>(i), value); env->DeleteLocalRef(value);
        }
        return result;
    }
}
/// Copies unread body bytes: the count, -1 at the end, or 0 when none arrived within [wait] milliseconds.
jint netRead(JNIEnv *env, jclass, jlong handle, jbyteArray buffer, jint wait) {
    @autoreleasepool {
        KTRNetState *state = netState(env, handle); if (state == nil) return -1;
        [state.condition lock];
        if (state.offset == state.body.length && !state.done) {
            [state.condition waitUntilDate:[NSDate dateWithTimeIntervalSinceNow:wait / 1000.0]];
        }
        NSUInteger available = state.body.length - state.offset;
        if (available > 0) {
            jsize count = static_cast<jsize>(std::min<NSUInteger>(available, env->GetArrayLength(buffer)));
            env->SetByteArrayRegion(buffer, 0, count, static_cast<const jbyte *>(state.body.bytes) + state.offset);
            state.offset += count;
            // Drop consumed bytes in large steps, not per read, so a long body is not shifted repeatedly.
            if (state.offset >= (1u << 20) && state.offset * 2 >= state.body.length) {
                [state.body replaceBytesInRange:NSMakeRange(0, state.offset) withBytes:nullptr length:0];
                state.offset = 0;
            }
            [state.condition unlock];
            return count;
        }
        NSError *error = state.error; BOOL done = state.done;
        [state.condition unlock];
        if (error != nil) { netFail(env, error); return -1; }
        return done ? -1 : 0;
    }
}
/// Releases the request, cancelling it when it is still running. Idempotent.
void netClose(JNIEnv *, jclass, jlong handle) {
    KTRNetState *state;
    {
        std::lock_guard<std::mutex> lock(netMutex);
        auto found = netStates.find(handle);
        if (found == netStates.end()) return;
        state = found->second; netStates.erase(found);
    }
    [state.task cancel];
}
}

// Message digests for the JCA provider org.skepsun.kototoro.ios.shims.IosDigestProvider: the interpreter-only JVM
// hashes about 1 MiB per second in Java; CommonCrypto does the same in milliseconds. The handle is the context's
// address, owned by one Java digest object (freed once by its Cleaner).
namespace {
enum DigestAlgorithm : jint { DigestMD5, DigestSHA1, DigestSHA256, DigestSHA384, DigestSHA512 };
struct Digest {
    jint algorithm;
    union { CC_MD5_CTX md5; CC_SHA1_CTX sha1; CC_SHA256_CTX sha256; CC_SHA512_CTX sha512; };
};
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations" // MD5 stays available for extensions' compatibility
void digestInit(Digest *digest) {
    switch (digest->algorithm) {
        case DigestMD5: CC_MD5_Init(&digest->md5); break;
        case DigestSHA1: CC_SHA1_Init(&digest->sha1); break;
        case DigestSHA256: CC_SHA256_Init(&digest->sha256); break;
        case DigestSHA384: CC_SHA384_Init(&digest->sha512); break;
        default: CC_SHA512_Init(&digest->sha512); break;
    }
}
jlong digestCreate(JNIEnv *env, jclass, jint algorithm) {
    if (algorithm < DigestMD5 || algorithm > DigestSHA512) { fail(env, "java/lang/IllegalArgumentException", "Unknown digest"); return 0; }
    auto *digest = new Digest();
    digest->algorithm = algorithm;
    digestInit(digest);
    return reinterpret_cast<jlong>(digest);
}
void digestUpdate(JNIEnv *env, jclass, jlong handle, jbyteArray data, jint offset, jint length) {
    if (data == nullptr || offset < 0 || length < 0 || offset > env->GetArrayLength(data) - length) {
        fail(env, "java/lang/ArrayIndexOutOfBoundsException", "Digest input range"); return;
    }
    if (length == 0) return;
    auto *digest = reinterpret_cast<Digest *>(handle);
    // No JNI calls while the array is pinned.
    auto *bytes = static_cast<const uint8_t *>(env->GetPrimitiveArrayCritical(data, nullptr));
    if (bytes == nullptr) return;
    const uint8_t *input = bytes + offset;
    auto size = static_cast<CC_LONG>(length);
    switch (digest->algorithm) {
        case DigestMD5: CC_MD5_Update(&digest->md5, input, size); break;
        case DigestSHA1: CC_SHA1_Update(&digest->sha1, input, size); break;
        case DigestSHA256: CC_SHA256_Update(&digest->sha256, input, size); break;
        case DigestSHA384: CC_SHA384_Update(&digest->sha512, input, size); break;
        default: CC_SHA512_Update(&digest->sha512, input, size); break;
    }
    env->ReleasePrimitiveArrayCritical(data, const_cast<uint8_t *>(bytes), JNI_ABORT);
}
/// The digest of everything since the last reset; the context starts over afterwards, like MessageDigest.digest().
jbyteArray digestFinish(JNIEnv *env, jclass, jlong handle) {
    auto *digest = reinterpret_cast<Digest *>(handle);
    uint8_t out[CC_SHA512_DIGEST_LENGTH];
    jsize length;
    switch (digest->algorithm) {
        case DigestMD5: CC_MD5_Final(out, &digest->md5); length = CC_MD5_DIGEST_LENGTH; break;
        case DigestSHA1: CC_SHA1_Final(out, &digest->sha1); length = CC_SHA1_DIGEST_LENGTH; break;
        case DigestSHA256: CC_SHA256_Final(out, &digest->sha256); length = CC_SHA256_DIGEST_LENGTH; break;
        case DigestSHA384: CC_SHA384_Final(out, &digest->sha512); length = CC_SHA384_DIGEST_LENGTH; break;
        default: CC_SHA512_Final(out, &digest->sha512); length = CC_SHA512_DIGEST_LENGTH; break;
    }
    digestInit(digest);
    jbyteArray result = env->NewByteArray(length);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(out));
    return result;
}
#pragma clang diagnostic pop
void digestReset(JNIEnv *, jclass, jlong handle) { digestInit(reinterpret_cast<Digest *>(handle)); }
/// MessageDigest.clone(): the contexts are plain structs, so the copy continues from the same state.
jlong digestCopy(JNIEnv *, jclass, jlong handle) { return reinterpret_cast<jlong>(new Digest(*reinterpret_cast<Digest *>(handle))); }
void digestFree(JNIEnv *, jclass, jlong handle) { delete reinterpret_cast<Digest *>(handle); }
}

extern "C" int ktr_native_install(JNIEnv *env) {
    jclass type = env->FindClass("org/skepsun/kototoro/ios/shims/IosNative");
    // The independent fixture profile does not use Android shims.
    if (type == nullptr) { env->ExceptionClear(); return 0; }
    JNINativeMethod base_methods[] = {
        {const_cast<char *>("decodeImage"), const_cast<char *>("([BZI)[I"), reinterpret_cast<void *>(decodeImage)},
        {const_cast<char *>("encodeImage"), const_cast<char *>("([IIILjava/lang/String;I)[B"), reinterpret_cast<void *>(encodeImage)},
        {const_cast<char *>("measureText"), const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;IF)[F"), reinterpret_cast<void *>(measureText)},
        {const_cast<char *>("drawText"), const_cast<char *>("([IIILjava/lang/String;FFLjava/lang/String;IFI[F)V"), reinterpret_cast<void *>(drawText)},
        {const_cast<char *>("openPdf"), const_cast<char *>("([B)J"), reinterpret_cast<void *>(openPdf)},
        {const_cast<char *>("pdfPageInfo"), const_cast<char *>("(JI)[I"), reinterpret_cast<void *>(pdfPageInfo)},
        {const_cast<char *>("renderPdf"), const_cast<char *>("(JI[III)V"), reinterpret_cast<void *>(renderPdf)},
        {const_cast<char *>("closePdf"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(closePdf)},
        {const_cast<char *>("createJavaScript"), const_cast<char *>("(Ljava/lang/Object;)J"), reinterpret_cast<void *>(createJavaScript)},
        {const_cast<char *>("evaluateJavaScript"), const_cast<char *>("(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void *>(evaluateJavaScript)},
        {const_cast<char *>("closeJavaScript"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(closeJavaScript)},
    };
    int result = env->RegisterNatives(type, base_methods, sizeof(base_methods) / sizeof(base_methods[0]));
    if (result != 0) {
        env->DeleteLocalRef(type);
        return result;
    }
    // Optional: network transport and CommonCrypto digest; older shim jars without them continue on JDK.
    JNINativeMethod extended_methods[] = {
        {const_cast<char *>("netAvailable"), const_cast<char *>("()Z"), reinterpret_cast<void *>(netAvailable)},
        {const_cast<char *>("netStart"), const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[BI)J"), reinterpret_cast<void *>(netStart)},
        {const_cast<char *>("netHead"), const_cast<char *>("(JI)[Ljava/lang/String;"), reinterpret_cast<void *>(netHead)},
        {const_cast<char *>("netRead"), const_cast<char *>("(J[BI)I"), reinterpret_cast<void *>(netRead)},
        {const_cast<char *>("netClose"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(netClose)},
        {const_cast<char *>("digestCreate"), const_cast<char *>("(I)J"), reinterpret_cast<void *>(digestCreate)},
        {const_cast<char *>("digestUpdate"), const_cast<char *>("(J[BII)V"), reinterpret_cast<void *>(digestUpdate)},
        {const_cast<char *>("digestFinish"), const_cast<char *>("(J)[B"), reinterpret_cast<void *>(digestFinish)},
        {const_cast<char *>("digestReset"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(digestReset)},
        {const_cast<char *>("digestCopy"), const_cast<char *>("(J)J"), reinterpret_cast<void *>(digestCopy)},
        {const_cast<char *>("digestFree"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(digestFree)},
    };
    if (env->RegisterNatives(type, extended_methods, sizeof(extended_methods) / sizeof(extended_methods[0])) != 0 ||
        env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(type);
    // Native digests take precedence for every MessageDigest user (image store, artifact checks, extensions).
    // Optional: an older shim jar without the provider keeps the JDK's digests.
    jclass provider = env->FindClass("org/skepsun/kototoro/ios/shims/IosDigestProvider");
    if (provider == nullptr) { env->ExceptionClear(); return 0; }
    jmethodID install = env->GetStaticMethodID(provider, "install", "()V");
    if (install != nullptr) env->CallStaticVoidMethod(provider, install);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    env->DeleteLocalRef(provider);
    return 0;
}

#if KTR_NATIVE_TEST_LIBRARY
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK || ktr_native_install(env) != 0) return JNI_ERR;
    return JNI_VERSION_1_8;
}
#endif
#endif
