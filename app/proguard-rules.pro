# MCA release shrinker rules. Keep the boundary contracts that are looked up
# by name from native code, reflection, or the Android manifest; ordinary UI
# and domain code remains shrinkable.

# Rhino's optional JavaBean-to-JSON converter references java.beans, which is
# absent on Android. MCA scripts only exchange JSON text across the host seam;
# they never receive Java objects, so this converter is outside the supported path.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters

# Every JNI entry point and its descriptor classes must retain their names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# AIDL/Binder worker surfaces are looked up by the framework and by the
# isolated worker process. Do not rename their generated members.
-keep interface com.muyuchat.mca.ILocalChatWorker** { *; }
-keep class com.muyuchat.mca.ILocalChatWorker** { *; }

# Native bridge classes expose @JNI methods by Java name.
-keep class com.muyuchat.core.nativebridge.** { *; }
-keep class com.muyuchat.core.sdnative.** { *; }
-keep class com.scsonic.qwenimage21.** { *; }

# LiteRT-LM native JNI resolves Java callbacks and value types by name.
-keep class com.google.ai.edge.litertlm.** { *; }

# GenieX SDK objects are inspected reflectively (method names are part of the
# compatibility contract with the packaged SDK).
-keep class com.geniex.sdk.** { *; }
-keep class com.qualcomm.qti.** { *; }

# Android components and Room-generated implementations remain discoverable.
-keep class * extends android.app.Service { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
