"""External dependencies for grpc-java."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

# For use with maven_install's artifacts.
# maven_install(
#     ...
#     artifacts = [
#         # Your own deps
#     ] + IO_GRPC_GRPC_JAVA_ARTIFACTS,
# )
# GRPC_DEPS_START
IO_GRPC_GRPC_JAVA_ARTIFACTS = [
    "com.google.android:annotations:4.1.1.4",
    "com.google.api.grpc:proto-google-common-protos:2.51.0",
    "com.google.auth:google-auth-library-credentials:1.24.1",
    "com.google.auth:google-auth-library-oauth2-http:1.24.1",
    "com.google.auto.value:auto-value-annotations:1.11.0",
    "com.google.auto.value:auto-value:1.11.0",
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.code.gson:gson:2.11.0",
    "com.google.errorprone:error_prone_annotations:2.30.0",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:guava:33.3.1-android",
    "com.google.re2j:re2j:1.8",
    "com.google.s2a.proto.v2:s2a-proto:0.1.1",
    "com.google.truth:truth:1.4.2",
    "com.squareup.okhttp:okhttp:2.7.5",
    "com.squareup.okio:okio:2.10.0",  # 3.0+ needs swapping to -jvm; need work to avoid flag-day
    "io.netty:netty-buffer:4.1.110.Final",
    "io.netty:netty-codec-http2:4.1.110.Final",
    "io.netty:netty-codec-http:4.1.110.Final",
    "io.netty:netty-codec-socks:4.1.110.Final",
    "io.netty:netty-codec:4.1.110.Final",
    "io.netty:netty-common:4.1.110.Final",
    "io.netty:netty-handler-proxy:4.1.110.Final",
    "io.netty:netty-handler:4.1.110.Final",
    "io.netty:netty-resolver:4.1.110.Final",
    "io.netty:netty-tcnative-boringssl-static:2.0.70.Final",
    "io.netty:netty-tcnative-classes:2.0.70.Final",
    "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.110.Final",
    "io.netty:netty-transport-native-unix-common:4.1.110.Final",
    "io.netty:netty-transport:4.1.110.Final",
    "io.opencensus:opencensus-api:0.31.0",
    "io.opencensus:opencensus-contrib-grpc-metrics:0.31.0",
    "io.perfmark:perfmark-api:0.27.0",
    "junit:junit:4.13.2",
    "org.checkerframework:checker-qual:3.12.0",
    "org.codehaus.mojo:animal-sniffer-annotations:1.24",
]
# GRPC_DEPS_END

# For use with maven_install's override_targets.
# maven_install(
#     ...
#     override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
# )
#
# If you have your own overrides as well, you can use:
#     override_targets = {
#         "your.target:artifact": "@//third_party/artifact",
#     } | IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS = {
    "com.google.protobuf:protobuf-java": "@com_google_protobuf//:protobuf_java",
    "com.google.protobuf:protobuf-java-util": "@com_google_protobuf//:protobuf_java_util",
    "com.google.protobuf:protobuf-javalite": "@com_google_protobuf//:protobuf_javalite",
    "io.grpc:grpc-alts": "@io_grpc_grpc_java//alts",
    "io.grpc:grpc-api": "@io_grpc_grpc_java//api",
    "io.grpc:grpc-auth": "@io_grpc_grpc_java//auth",
    "io.grpc:grpc-census": "@io_grpc_grpc_java//census",
    "io.grpc:grpc-context": "@io_grpc_grpc_java//context",
    "io.grpc:grpc-core": "@io_grpc_grpc_java//core:core_maven",
    "io.grpc:grpc-googleapis": "@io_grpc_grpc_java//googleapis",
    "io.grpc:grpc-grpclb": "@io_grpc_grpc_java//grpclb",
    "io.grpc:grpc-inprocess": "@io_grpc_grpc_java//inprocess",
    "io.grpc:grpc-netty": "@io_grpc_grpc_java//netty",
    "io.grpc:grpc-netty-shaded": "@io_grpc_grpc_java//netty:shaded_maven",
    "io.grpc:grpc-okhttp": "@io_grpc_grpc_java//okhttp",
    "io.grpc:grpc-protobuf": "@io_grpc_grpc_java//protobuf",
    "io.grpc:grpc-protobuf-lite": "@io_grpc_grpc_java//protobuf-lite",
    "io.grpc:grpc-rls": "@io_grpc_grpc_java//rls",
    "io.grpc:grpc-services": "@io_grpc_grpc_java//services:services_maven",
    "io.grpc:grpc-stub": "@io_grpc_grpc_java//stub",
    "io.grpc:grpc-s2a": "@io_grpc_grpc_java//s2a",
    "io.grpc:grpc-testing": "@io_grpc_grpc_java//testing",
    "io.grpc:grpc-xds": "@io_grpc_grpc_java//xds:xds_maven",
    "io.grpc:grpc-util": "@io_grpc_grpc_java//util",
}

def grpc_java_repositories(bzlmod = False):
    """Imports dependencies for grpc-java."""
    if not bzlmod and not native.existing_rule("dev_cel"):
        http_archive(
            name = "dev_cel",
            strip_prefix = "cel-spec-0.15.0",
            sha256 = "3ee09eb69dbe77722e9dee23dc48dc2cd9f765869fcf5ffb1226587c81791a0b",
            urls = [
                "https://github.com/google/cel-spec/archive/refs/tags/v0.15.0.tar.gz",
            ],
        )
    if not native.existing_rule("com_github_cncf_xds"):
        http_archive(
            name = "com_github_cncf_xds",
            strip_prefix = "xds-2ac532fd44436293585084f8d94c6bdb17835af0",
            sha256 = "790c4c83b6950bb602fec221f6a529d9f368cdc8852aae7d2592d0d04b015f37",
            urls = [
                "https://github.com/cncf/xds/archive/2ac532fd44436293585084f8d94c6bdb17835af0.tar.gz",
            ],
        )
    if not bzlmod and not native.existing_rule("com_github_grpc_grpc"):
        http_archive(
            name = "com_github_grpc_grpc",
            strip_prefix = "grpc-1.46.0",
            sha256 = "67423a4cd706ce16a88d1549297023f0f9f0d695a96dd684adc21e67b021f9bc",
            urls = [
                "https://github.com/grpc/grpc/archive/v1.46.0.tar.gz",
            ],
        )
    if not bzlmod and not native.existing_rule("com_google_protobuf"):
        com_google_protobuf()
    if not bzlmod and not native.existing_rule("com_google_googleapis"):
        http_archive(
            name = "com_google_googleapis",
            sha256 = "49930468563dd48283e8301e8d4e71436bf6d27ac27c235224cc1a098710835d",
            strip_prefix = "googleapis-ca1372c6d7bcb199638ebfdb40d2b2660bab7b88",
            urls = [
                "https://github.com/googleapis/googleapis/archive/ca1372c6d7bcb199638ebfdb40d2b2660bab7b88.tar.gz",
            ],
        )
    if not bzlmod and not native.existing_rule("io_bazel_rules_go"):
        http_archive(
            name = "io_bazel_rules_go",
            sha256 = "ab21448cef298740765f33a7f5acee0607203e4ea321219f2a4c85a6e0fb0a27",
            urls = [
                "https://mirror.bazel.build/github.com/bazelbuild/rules_go/releases/download/v0.32.0/rules_go-v0.32.0.zip",
                "https://github.com/bazelbuild/rules_go/releases/download/v0.32.0/rules_go-v0.32.0.zip",
            ],
        )
    if not native.existing_rule("io_grpc_grpc_proto"):
        io_grpc_grpc_proto()
    if not native.existing_rule("envoy_api"):
        http_archive(
            name = "envoy_api",
            sha256 = "ecf71817233eba19cc8b4ee14e126ffd5838065d5b5a92b2506258a42ac55199",
            strip_prefix = "data-plane-api-0bc95493c5e88b7b07e62758d23b39341813a827",
            urls = [
                "https://github.com/envoyproxy/data-plane-api/archive/0bc95493c5e88b7b07e62758d23b39341813a827.tar.gz",
            ],
        )

def com_google_protobuf():
    # proto_library rules implicitly depend on @com_google_protobuf//:protoc,
    # which is the proto-compiler.
    # This statement defines the @com_google_protobuf repo.
    http_archive(
        name = "com_google_protobuf",
        sha256 = "9bd87b8280ef720d3240514f884e56a712f2218f0d693b48050c836028940a42",
        strip_prefix = "protobuf-25.1",
        urls = ["https://github.com/protocolbuffers/protobuf/releases/download/v25.1/protobuf-25.1.tar.gz"],
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "729ac127a003836d539ed9da72a21e094aac4c4609e0481d6fc9e28a844e11af",
        strip_prefix = "grpc-proto-4f245d272a28a680606c0739753506880cf33b5f",
        urls = ["https://github.com/grpc/grpc-proto/archive/4f245d272a28a680606c0739753506880cf33b5f.zip"],
    )

def _grpc_java_repositories_extension(_):
    grpc_java_repositories(bzlmod = True)

grpc_java_repositories_extension = module_extension(implementation = _grpc_java_repositories_extension)
