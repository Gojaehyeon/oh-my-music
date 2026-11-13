// PC 채점 도구: :core 분석 경로 + 같은 ONNX 모델로 오디오 파일의 키를 낸다(eval/android_eval.py 가 호출).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}

application { mainClass.set("kr.tntlabs.ohmymusic.eval.MainKt") }
