# codegraph 内核(XINCODE 集成说明)

上游:https://github.com/colbymchenry/codegraph — **MIT 许可**,见 `LICENSE.codegraph`。

## 改了什么

上游的 Rust 抽取内核**原样引入,未改动任何抽取逻辑**。只加了两处:

1. `src/jni_api.rs` —— 新文件。Android JNI 出口,把内核输出的二进制表解码成 JSON。
2. `src/lib.rs` —— 加了 `extract_raw()`,把原本裹在 napi 类型里的分发逻辑抽出来供 JNI 复用;
   上游的 `extract_file()` 改为调用它,对外行为完全不变。

## 为什么 JNI 层返回 JSON

内核内部用紧凑二进制(定长行 + 字符串池),上游在 TypeScript 里写了对应解码器。
若把 buffer 原样丢过 JNI,就得在 Kotlin 里把解码逻辑再实现一遍 —— 行宽/字段顺序/偏移
一旦对不上,症状是**解出乱码而不是报错**,极难查,而且内核改布局要同步两处。
在 Rust 侧解码只写一次,常量与布局定义就在隔壁文件,对不上会编译失败。

## 构建

```bash
ANDROID_NDK_HOME=<ndk> ./codegraph-kernel/build-android.sh
```

产物落到 `app/src/main/jniLibs/arm64-v8a/`,**不进 git**(34MB,每次重编都是新 blob)。
CI 每次构建时现编,见 `.github/workflows/build.yml`。

只编 arm64-v8a:每多一个 ABI,APK 就多 34MB,而 2019 年后的设备基本都是 arm64。
