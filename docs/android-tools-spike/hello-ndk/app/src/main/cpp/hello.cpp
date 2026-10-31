// JNI function using std::string, std::vector, exceptions and RTTI, plus a third-party C library (criterion d).
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

#include "tinyexpr.h"

namespace {

int checked_sum(const std::vector<int>& values) {
    if (values.empty()) throw std::invalid_argument("empty vector");
    return std::accumulate(values.begin(), values.end(), 0);
}

std::string report() {
    std::vector<int> values{5, 3, 8, 1};
    std::sort(values.begin(), values.end());

    std::string out = "sorted:";
    for (int v : values) out += " " + std::to_string(v);
    out += "\nsum: " + std::to_string(checked_sum(values));

    try {
        checked_sum({});
        out += "\nexception: NOT THROWN";
    } catch (const std::exception& e) {
        out += std::string("\nexception caught: ") + e.what();
    }

    out += "\ntinyexpr 2*(3+4)^2 = " + std::to_string(te_interp("2*(3+4)^2", nullptr));
    out += "\nAPI floor " + std::to_string(__ANDROID_API__);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_hellondk_MainActivity_nativeReport(JNIEnv* env, jobject) {
    __android_log_print(ANDROID_LOG_INFO, "HelloNdk", "nativeReport called");
    return env->NewStringUTF(report().c_str());
}
