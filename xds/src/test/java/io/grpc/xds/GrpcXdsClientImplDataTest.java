/*
 * Copyright 2021 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.envoyproxy.envoy.config.route.v3.RouteAction.ClusterSpecifierCase.CLUSTER_SPECIFIER_PLUGIN;
import static io.grpc.xds.XdsClusterResource.TRANSPORT_SOCKET_NAME_HTTP11_PROXY;
import static io.grpc.xds.XdsEndpointResource.GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.FloatValue;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.core.v3.PathConfigSource;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.config.core.v3.SelfConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.listener.v3.ListenerFilter;
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction;
import io.envoyproxy.envoy.config.route.v3.FilterAction;
import io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.envoyproxy.envoy.config.route.v3.RedirectAction;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RetryBackOff;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.ConnectionProperties;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.FilterState;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.Header;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.QueryParameter;
import io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.extensions.filters.common.fault.v3.FaultDelay;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.envoyproxy.envoy.extensions.filters.http.gcp_authn.v3.Audience;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBACPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.load_balancing_policies.client_side_weighted_round_robin.v3.ClientSideWeightedRoundRobin;
import io.envoyproxy.envoy.extensions.load_balancing_policies.wrr_locality.v3.WrrLocality;
import io.envoyproxy.envoy.extensions.transport_sockets.http_11_proxy.v3.Http11ProxyUpstreamTransport;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateProviderPluginInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsParameters;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatchAndSubstitute;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher.GoogleRE2;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.envoyproxy.envoy.type.v3.Int64Range;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InsecureChannelCredentials;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status.Code;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.lookup.v1.GrpcKeyBuilder;
import io.grpc.lookup.v1.GrpcKeyBuilder.Name;
import io.grpc.lookup.v1.NameMatcher;
import io.grpc.lookup.v1.RouteLookupClusterSpecifier;
import io.grpc.lookup.v1.RouteLookupConfig;
import io.grpc.xds.ClusterSpecifierPlugin.NamedPluginConfig;
import io.grpc.xds.ClusterSpecifierPlugin.PluginConfig;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.GcpAuthenticationFilter.AudienceMetadataParser.AudienceWrapper;
import io.grpc.xds.MetadataRegistry.MetadataValueParser;
import io.grpc.xds.RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SuppressWarnings("DataFlowIssue")
@RunWith(JUnit4.class)
public class GrpcXdsClientImplDataTest {

  private static final FaultFilter.Provider FAULT_FILTER_PROVIDER = new FaultFilter.Provider();
  private static final RbacFilter.Provider RBAC_FILTER_PROVIDER = new RbacFilter.Provider();
  private static final RouterFilter.Provider ROUTER_FILTER_PROVIDER = new RouterFilter.Provider();

  private static final ServerInfo LRS_SERVER_INFO =
      ServerInfo.create("lrs.googleapis.com", InsecureChannelCredentials.create());
  private static final String GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE =
      "GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE";

  private final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private boolean originalEnableRouteLookup;
  private boolean originalEnableLeastRequest;
  private boolean originalEnableUseSystemRootCerts;

  @Before
  public void setUp() {
    originalEnableRouteLookup = XdsRouteConfigureResource.enableRouteLookup;
    originalEnableLeastRequest = XdsClusterResource.enableLeastRequest;
    originalEnableUseSystemRootCerts = XdsClusterResource.enableSystemRootCerts;
  }

  @After
  public void tearDown() {
    XdsRouteConfigureResource.enableRouteLookup = originalEnableRouteLookup;
    XdsClusterResource.enableLeastRequest = originalEnableLeastRequest;
    XdsClusterResource.enableSystemRootCerts = originalEnableUseSystemRootCerts;
  }

  @Test
  public void parseRoute_withRouteAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    StructOrError<Route> struct = XdsRouteConfigureResource.parseRoute(
        proto, filterRegistry, ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.forAction(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                RouteAction.forCluster(
                    "cluster-foo", Collections.<HashPolicy>emptyList(), null, null, false),
                ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRoute_withNonForwardingAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setNonForwardingAction(NonForwardingAction.getDefaultInstance())
            .build();
    StructOrError<Route> struct = XdsRouteConfigureResource.parseRoute(
        proto, filterRegistry, ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.forNonForwardingAction(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRoute_withUnsupportedActionTypes() {
    StructOrError<Route> res;
    io.envoyproxy.envoy.config.route.v3.Route redirectRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setRedirect(RedirectAction.getDefaultInstance())
            .build();
    res = XdsRouteConfigureResource.parseRoute(
        redirectRoute, filterRegistry, ImmutableMap.of(), ImmutableSet.of(),
        getXdsResourceTypeArgs(true));
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: REDIRECT");

    io.envoyproxy.envoy.config.route.v3.Route directResponseRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setDirectResponse(DirectResponseAction.getDefaultInstance())
            .build();
    res = XdsRouteConfigureResource.parseRoute(
        directResponseRoute, filterRegistry, ImmutableMap.of(), ImmutableSet.of(),
        getXdsResourceTypeArgs(true));
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: DIRECT_RESPONSE");

    io.envoyproxy.envoy.config.route.v3.Route filterRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setFilterAction(FilterAction.getDefaultInstance())
            .build();
    res = XdsRouteConfigureResource.parseRoute(
        filterRoute, filterRegistry, ImmutableMap.of(), ImmutableSet.of(),
        getXdsResourceTypeArgs(true));
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: FILTER_ACTION");
  }

  @Test
  public void parseRoute_skipRouteWithUnsupportedMatcher() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method")
                    .addQueryParameters(
                        io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher
                            .getDefaultInstance()))  // query parameter not supported
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    assertThat(XdsRouteConfigureResource.parseRoute(
            proto, filterRegistry, ImmutableMap.of(), ImmutableSet.of(),
            getXdsResourceTypeArgs(true)))
        .isNull();
  }

  @Test
  public void parseRoute_skipRouteWithUnsupportedAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setClusterHeader("cluster header"))  // cluster_header action not supported
            .build();
    assertThat(XdsRouteConfigureResource.parseRoute(
            proto, filterRegistry, ImmutableMap.of(), ImmutableSet.of(),
            getXdsResourceTypeArgs(true)))
        .isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseRouteMatch_withHeaderMatcher() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPrefix("")
            .addHeaders(
                io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName(":scheme")
                    .setPrefixMatch("http"))
            .addHeaders(
                io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName(":method")
                    .setExactMatch("PUT"))
            .build();
    StructOrError<RouteMatch> struct = XdsRouteConfigureResource.parseRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            RouteMatch.create(
                PathMatcher.fromPrefix("", false),
                Arrays.asList(
                    HeaderMatcher.forPrefix(":scheme", "http", false),
                    HeaderMatcher.forExactValue(":method", "PUT", false)),
                null));
  }

  @Test
  public void parseRouteMatch_withRuntimeFractionMatcher() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPrefix("")
            .setRuntimeFraction(
                RuntimeFractionalPercent.newBuilder()
                    .setDefaultValue(
                        FractionalPercent.newBuilder()
                            .setNumerator(30)
                            .setDenominator(FractionalPercent.DenominatorType.HUNDRED)))
            .build();
    StructOrError<RouteMatch> struct = XdsRouteConfigureResource.parseRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            RouteMatch.create(
                PathMatcher.fromPrefix( "", false), Collections.<HeaderMatcher>emptyList(),
                FractionMatcher.create(30, 100)));
  }

  @Test
  public void parsePathMatcher_withFullPath() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPath("/service/method")
            .build();
    StructOrError<PathMatcher> struct = XdsRouteConfigureResource.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        PathMatcher.fromPath("/service/method", false));
  }

  @Test
  public void parsePathMatcher_withPrefix() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix("/").build();
    StructOrError<PathMatcher> struct = XdsRouteConfigureResource.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        PathMatcher.fromPrefix("/", false));
  }

  @Test
  public void parsePathMatcher_withSafeRegEx() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setSafeRegex(RegexMatcher.newBuilder().setRegex("."))
            .build();
    StructOrError<PathMatcher> struct = XdsRouteConfigureResource.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(PathMatcher.fromRegEx(Pattern.compile(".")));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withExactMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setExactMatch("PUT")
            .build();
    StructOrError<HeaderMatcher> struct1 = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        HeaderMatcher.forExactValue(":method", "PUT", false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withSafeRegExMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("P*"))
            .build();
    StructOrError<HeaderMatcher> struct3 = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct3.getErrorDetail()).isNull();
    assertThat(struct3.getStruct()).isEqualTo(
        HeaderMatcher.forSafeRegEx(":method", Pattern.compile("P*"), false));
  }

  @Test
  public void parseHeaderMatcher_withRangeMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("timeout")
            .setRangeMatch(Int64Range.newBuilder().setStart(10L).setEnd(20L))
            .build();
    StructOrError<HeaderMatcher> struct4 = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        HeaderMatcher.forRange("timeout", HeaderMatcher.Range.create(10L, 20L), false));
  }

  @Test
  public void parseHeaderMatcher_withPresentMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("user-agent")
            .setPresentMatch(true)
            .build();
    StructOrError<HeaderMatcher> struct5 = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct5.getErrorDetail()).isNull();
    assertThat(struct5.getStruct()).isEqualTo(
        HeaderMatcher.forPresent("user-agent", true, false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withPrefixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setPrefixMatch("service-foo")
            .build();
    StructOrError<HeaderMatcher> struct6 = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct6.getErrorDetail()).isNull();
    assertThat(struct6.getStruct()).isEqualTo(
        HeaderMatcher.forPrefix("authority", "service-foo", false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withSuffixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setSuffixMatch("googleapis.com")
            .build();
    StructOrError<HeaderMatcher> struct7 = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct7.getErrorDetail()).isNull();
    assertThat(struct7.getStruct()).isEqualTo(
        HeaderMatcher.forSuffix("authority", "googleapis.com", false));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_malformedRegExPattern() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("["))
            .build();
    StructOrError<HeaderMatcher> struct = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct.getErrorDetail()).isNotNull();
    assertThat(struct.getStruct()).isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseHeaderMatcher_withStringMatcher() {
    io.envoyproxy.envoy.type.matcher.v3.StringMatcher stringMatcherProto =
            io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
                    .setPrefix("service-foo")
                    .setIgnoreCase(false)
                    .build();

    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
            io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName("authority")
                    .setStringMatch(stringMatcherProto)
                    .setInvertMatch(false)
                    .build();
    StructOrError<HeaderMatcher> struct = XdsRouteConfigureResource.parseHeaderMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
            HeaderMatcher.forString("authority", Matchers.StringMatcher
                    .forPrefix("service-foo", false), false));
  }

  @Test
  public void parseRouteAction_withCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
          ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isEqualTo("cluster-foo");
    assertThat(struct.getStruct().weightedClusters()).isNull();
    assertThat(struct.getStruct().autoHostRewrite()).isFalse();
  }

  @Test
  public void parseRouteAction_withCluster_autoHostRewriteEnabled() {
    System.setProperty(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE, "true");
    try {
      io.envoyproxy.envoy.config.route.v3.RouteAction proto =
          io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
              .setCluster("cluster-foo")
              .setAutoHostRewrite(BoolValue.of(true))
              .build();
      StructOrError<RouteAction> struct =
          XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
              ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
      assertThat(struct.getErrorDetail()).isNull();
      assertThat(struct.getStruct().cluster()).isEqualTo("cluster-foo");
      assertThat(struct.getStruct().weightedClusters()).isNull();
      assertThat(struct.getStruct().autoHostRewrite()).isTrue();
    } finally {
      System.clearProperty(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE);
    }
  }

  @Test
  public void parseRouteAction_withCluster_flagDisabled_autoHostRewriteNotEnabled() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setAutoHostRewrite(BoolValue.of(true))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isEqualTo("cluster-foo");
    assertThat(struct.getStruct().weightedClusters()).isNull();
    assertThat(struct.getStruct().autoHostRewrite()).isFalse();
  }

  @Test
  public void parseRouteAction_withWeightedCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setWeightedClusters(
                WeightedCluster.newBuilder()
                    .addClusters(
                        WeightedCluster.ClusterWeight
                            .newBuilder()
                            .setName("cluster-foo")
                            .setWeight(UInt32Value.newBuilder().setValue(30)))
                    .addClusters(WeightedCluster.ClusterWeight
                        .newBuilder()
                        .setName("cluster-bar")
                        .setWeight(UInt32Value.newBuilder().setValue(70))))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
          ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isNull();
    assertThat(struct.getStruct().weightedClusters()).containsExactly(
        ClusterWeight.create("cluster-foo", 30, ImmutableMap.<String, FilterConfig>of()),
        ClusterWeight.create("cluster-bar", 70, ImmutableMap.<String, FilterConfig>of()));
    assertThat(struct.getStruct().autoHostRewrite()).isFalse();
  }

  @Test
  public void parseRouteAction_withWeightedCluster_autoHostRewriteEnabled() {
    System.setProperty(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE, "true");
    try {
      io.envoyproxy.envoy.config.route.v3.RouteAction proto =
          io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
              .setWeightedClusters(
                  WeightedCluster.newBuilder()
                      .addClusters(
                          WeightedCluster.ClusterWeight
                              .newBuilder()
                              .setName("cluster-foo")
                              .setWeight(UInt32Value.newBuilder().setValue(30)))
                      .addClusters(WeightedCluster.ClusterWeight
                          .newBuilder()
                          .setName("cluster-bar")
                          .setWeight(UInt32Value.newBuilder().setValue(70))))
              .setAutoHostRewrite(BoolValue.of(true))
              .build();
      StructOrError<RouteAction> struct =
          XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
              ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
      assertThat(struct.getErrorDetail()).isNull();
      assertThat(struct.getStruct().cluster()).isNull();
      assertThat(struct.getStruct().weightedClusters()).containsExactly(
          ClusterWeight.create("cluster-foo", 30, ImmutableMap.<String, FilterConfig>of()),
          ClusterWeight.create("cluster-bar", 70, ImmutableMap.<String, FilterConfig>of()));
      assertThat(struct.getStruct().autoHostRewrite()).isTrue();
    } finally {
      System.clearProperty(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE);
    }
  }

  @Test
  public void parseRouteAction_withWeightedCluster_flagDisabled_autoHostRewriteDisabled() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setWeightedClusters(
                WeightedCluster.newBuilder()
                    .addClusters(
                        WeightedCluster.ClusterWeight
                            .newBuilder()
                            .setName("cluster-foo")
                            .setWeight(UInt32Value.newBuilder().setValue(30)))
                    .addClusters(WeightedCluster.ClusterWeight
                        .newBuilder()
                        .setName("cluster-bar")
                        .setWeight(UInt32Value.newBuilder().setValue(70))))
            .setAutoHostRewrite(BoolValue.of(true))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isNull();
    assertThat(struct.getStruct().weightedClusters()).containsExactly(
        ClusterWeight.create("cluster-foo", 30, ImmutableMap.<String, FilterConfig>of()),
        ClusterWeight.create("cluster-bar", 70, ImmutableMap.<String, FilterConfig>of()));
    assertThat(struct.getStruct().autoHostRewrite()).isFalse();
  }

  @Test
  public void parseRouteAction_weightedClusterSum() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setWeightedClusters(
                WeightedCluster.newBuilder()
                    .addClusters(
                        WeightedCluster.ClusterWeight
                            .newBuilder()
                            .setName("cluster-foo")
                            .setWeight(UInt32Value.newBuilder().setValue(0)))
                    .addClusters(WeightedCluster.ClusterWeight
                        .newBuilder()
                        .setName("cluster-bar")
                        .setWeight(UInt32Value.newBuilder().setValue(0))))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isEqualTo("Sum of cluster weights should be above 0.");
  }

  @Test
  public void parseRouteAction_withTimeoutByGrpcTimeoutHeaderMax() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setMaxStreamDuration(
                MaxStreamDuration.newBuilder()
                    .setGrpcTimeoutHeaderMax(Durations.fromSeconds(5L))
                    .setMaxStreamDuration(Durations.fromMillis(20L)))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutByMaxStreamDuration() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setMaxStreamDuration(
                MaxStreamDuration.newBuilder()
                    .setMaxStreamDuration(Durations.fromSeconds(5L)))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
           ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutUnset() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
          ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().timeoutNano()).isNull();
  }

  @Test
  public void parseRouteAction_withRetryPolicy() {
    RetryPolicy.Builder builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn(
            "cancelled,deadline-exceeded,internal,resource-exhausted,unavailable");
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setRetryPolicy(builder.build())
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
          ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    RouteAction.RetryPolicy retryPolicy = struct.getStruct().retryPolicy();
    assertThat(retryPolicy.maxAttempts()).isEqualTo(4);
    assertThat(retryPolicy.initialBackoff()).isEqualTo(Durations.fromMillis(500));
    assertThat(retryPolicy.maxBackoff()).isEqualTo(Durations.fromMillis(600));
    // Not supporting per_try_timeout yet.
    assertThat(retryPolicy.perAttemptRecvTimeout()).isEqualTo(null);
    assertThat(retryPolicy.retryableStatusCodes()).containsExactly(
        Code.CANCELLED, Code.DEADLINE_EXCEEDED, Code.INTERNAL, Code.RESOURCE_EXHAUSTED,
        Code.UNAVAILABLE);

    // empty retry_on
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300)); // Not supporting per_try_timeout yet.
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder.build())
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().retryPolicy()).isNotNull();
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes()).isEmpty();

    // base_interval unset
    builder
        .setRetryOn("cancelled")
        .setRetryBackOff(RetryBackOff.newBuilder().setMaxInterval(Durations.fromMillis(600)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail()).isEqualTo("No base_interval specified in retry_backoff");

    // max_interval unset
    builder.setRetryBackOff(RetryBackOff.newBuilder().setBaseInterval(Durations.fromMillis(500)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    retryPolicy = struct.getStruct().retryPolicy();
    assertThat(retryPolicy.maxBackoff()).isEqualTo(Durations.fromMillis(500 * 10));

    // base_interval < 0
    builder.setRetryBackOff(RetryBackOff.newBuilder().setBaseInterval(Durations.fromMillis(-1)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail())
        .isEqualTo("base_interval in retry_backoff must be positive");

    // base_interval > max_interval > 1ms
    builder.setRetryBackOff(
        RetryBackOff.newBuilder()
            .setBaseInterval(Durations.fromMillis(200)).setMaxInterval(Durations.fromMillis(100)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail())
        .isEqualTo("max_interval in retry_backoff cannot be less than base_interval");

    // 1ms > base_interval > max_interval
    builder.setRetryBackOff(
        RetryBackOff.newBuilder()
            .setBaseInterval(Durations.fromNanos(200)).setMaxInterval(Durations.fromNanos(100)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getErrorDetail())
        .isEqualTo("max_interval in retry_backoff cannot be less than base_interval");

    // 1ms > max_interval > base_interval
    builder.setRetryBackOff(
        RetryBackOff.newBuilder()
            .setBaseInterval(Durations.fromNanos(100)).setMaxInterval(Durations.fromNanos(200)));
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().retryPolicy().initialBackoff())
        .isEqualTo(Durations.fromMillis(1));
    assertThat(struct.getStruct().retryPolicy().maxBackoff())
        .isEqualTo(Durations.fromMillis(1));

    // retry_backoff unset
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("cancelled");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    retryPolicy = struct.getStruct().retryPolicy();
    assertThat(retryPolicy.initialBackoff()).isEqualTo(Durations.fromMillis(25));
    assertThat(retryPolicy.maxBackoff()).isEqualTo(Durations.fromMillis(250));

    // unsupported retry_on value
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("cancelled,unsupported-foo");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes())
        .containsExactly(Code.CANCELLED);

    // unsupported retry_on code
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("cancelled,abort");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes())
        .containsExactly(Code.CANCELLED);

    // whitespace in retry_on
    builder = RetryPolicy.newBuilder()
        .setNumRetries(UInt32Value.of(3))
        .setRetryBackOff(
            RetryBackOff.newBuilder()
                .setBaseInterval(Durations.fromMillis(500))
                .setMaxInterval(Durations.fromMillis(600)))
        .setPerTryTimeout(Durations.fromMillis(300))
        .setRetryOn("abort, , cancelled , ");
    proto = io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
        .setCluster("cluster-foo")
        .setRetryPolicy(builder)
        .build();
    struct = XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
        ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct().retryPolicy().retryableStatusCodes())
        .containsExactly(Code.CANCELLED);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void parseRouteAction_withHashPolicies() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setHeader(
                        Header.newBuilder()
                            .setHeaderName("user-agent")
                            .setRegexRewrite(
                                RegexMatchAndSubstitute.newBuilder()
                                    .setPattern(
                                        RegexMatcher.newBuilder()
                                            .setGoogleRe2(GoogleRE2.getDefaultInstance())
                                            .setRegex("grpc.*"))
                                    .setSubstitution("gRPC"))))
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setConnectionProperties(ConnectionProperties.newBuilder().setSourceIp(true))
                    .setTerminal(true))  // unsupported
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setFilterState(
                        FilterState.newBuilder()
                            .setKey(XdsRouteConfigureResource.HASH_POLICY_FILTER_STATE_KEY)))
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setQueryParameter(
                        QueryParameter.newBuilder().setName("param"))) // unsupported
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    List<HashPolicy> policies = struct.getStruct().hashPolicies();
    assertThat(policies).hasSize(2);
    assertThat(policies.get(0).type()).isEqualTo(HashPolicy.Type.HEADER);
    assertThat(policies.get(0).headerName()).isEqualTo("user-agent");
    assertThat(policies.get(0).isTerminal()).isFalse();
    assertThat(policies.get(0).regEx().pattern()).isEqualTo("grpc.*");
    assertThat(policies.get(0).regExSubstitution()).isEqualTo("gRPC");

    assertThat(policies.get(1).type()).isEqualTo(HashPolicy.Type.CHANNEL_ID);
    assertThat(policies.get(1).isTerminal()).isFalse();
  }

  @Test
  public void parseRouteAction_custerSpecifierNotSet() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct).isNull();
  }

  @Test
  public void parseRouteAction_clusterSpecifier_routeLookupDisabled() {
    XdsRouteConfigureResource.enableRouteLookup = false;
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setClusterSpecifierPlugin(CLUSTER_SPECIFIER_PLUGIN.name())
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(), ImmutableSet.of(), getXdsResourceTypeArgs(true));
    assertThat(struct).isNull();
  }

  @Test
  public void parseRouteAction_clusterSpecifier() {
    XdsRouteConfigureResource.enableRouteLookup = true;
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setClusterSpecifierPlugin(CLUSTER_SPECIFIER_PLUGIN.name())
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(CLUSTER_SPECIFIER_PLUGIN.name(), RlsPluginConfig.create(
                ImmutableMap.of("lookupService", "rls-cbt.googleapis.com"))), ImmutableSet.of(),
                getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct()).isNotNull();
    assertThat(struct.getStruct().autoHostRewrite()).isFalse();
  }

  @Test
  public void parseRouteAction_clusterSpecifier_autoHostRewriteEnabled() {
    System.setProperty(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE, "true");
    try {
      XdsRouteConfigureResource.enableRouteLookup = true;
      io.envoyproxy.envoy.config.route.v3.RouteAction proto =
          io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
              .setClusterSpecifierPlugin(CLUSTER_SPECIFIER_PLUGIN.name())
              .setAutoHostRewrite(BoolValue.of(true))
              .build();
      StructOrError<RouteAction> struct =
          XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
              ImmutableMap.of(CLUSTER_SPECIFIER_PLUGIN.name(), RlsPluginConfig.create(
                  ImmutableMap.of("lookupService", "rls-cbt.googleapis.com"))), ImmutableSet.of(),
              getXdsResourceTypeArgs(true));
      assertThat(struct.getStruct()).isNotNull();
      assertThat(struct.getStruct().autoHostRewrite()).isTrue();
    } finally {
      System.clearProperty(GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE);
    }
  }

  @Test
  public void parseRouteAction_clusterSpecifier_flagDisabled_autoHostRewriteDisabled() {
    XdsRouteConfigureResource.enableRouteLookup = true;
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setClusterSpecifierPlugin(CLUSTER_SPECIFIER_PLUGIN.name())
            .setAutoHostRewrite(BoolValue.of(true))
            .build();
    StructOrError<RouteAction> struct =
        XdsRouteConfigureResource.parseRouteAction(proto, filterRegistry,
            ImmutableMap.of(CLUSTER_SPECIFIER_PLUGIN.name(), RlsPluginConfig.create(
                ImmutableMap.of("lookupService", "rls-cbt.googleapis.com"))), ImmutableSet.of(),
            getXdsResourceTypeArgs(true));
    assertThat(struct.getStruct()).isNotNull();
    assertThat(struct.getStruct().autoHostRewrite()).isFalse();
  }

  @Test
  public void parseClusterWeight() {
    io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto =
        io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight.newBuilder()
            .setName("cluster-foo")
            .setWeight(UInt32Value.newBuilder().setValue(30))
            .build();
    ClusterWeight clusterWeight =
        XdsRouteConfigureResource.parseClusterWeight(proto, filterRegistry).getStruct();
    assertThat(clusterWeight.name()).isEqualTo("cluster-foo");
    assertThat(clusterWeight.weight()).isEqualTo(30);
  }

  @Test
  public void parseLocalityLbEndpoints_withHealthyEndpoints() throws ResourceInvalidException {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = XdsEndpointResource.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888,
                20, true, "", ImmutableMap.of())),
            100, 1, ImmutableMap.of()));
  }

  @Test
  public void parseLocalityLbEndpoints_onlyPermitIp() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("example.com").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    ResourceInvalidException ex = assertThrows(
        ResourceInvalidException.class,
        () -> XdsEndpointResource.parseLocalityLbEndpoints(proto));
    assertThat(ex.getMessage()).contains("IP");
    assertThat(ex.getMessage()).contains("example.com");
  }

  @Test
  public void parseLocalityLbEndpoints_treatUnknownHealthAsHealthy()
      throws ResourceInvalidException {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = XdsEndpointResource.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888,
                20, true, "", ImmutableMap.of())),
            100, 1, ImmutableMap.of()));
  }

  @Test
  public void parseLocalityLbEndpoints_withUnHealthyEndpoints() throws ResourceInvalidException {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNHEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = XdsEndpointResource.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20,
                false, "", ImmutableMap.of())),
            100, 1, ImmutableMap.of()));
  }

  @Test
  public void parseLocalityLbEndpoints_ignorZeroWeightLocality() throws ResourceInvalidException {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(0))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    assertThat(XdsEndpointResource.parseLocalityLbEndpoints(proto)).isNull();
  }

  @Test
  public void parseLocalityLbEndpoints_withDualStackEndpoints() {
    String originalDualStackProp =
        System.setProperty(GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS, "true");
    String v4Address = "172.14.14.5";
    String v6Address = "2001:db8::1";
    int port = 8888;

    try {
      io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
          io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
              .setLocality(Locality.newBuilder()
                  .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
              .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
              .setPriority(1)
              .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                  .setEndpoint(Endpoint.newBuilder()
                      .setAddress(Address.newBuilder()
                          .setSocketAddress(
                              SocketAddress.newBuilder()
                                  .setAddress(v4Address).setPortValue(port)))
                      .addAdditionalAddresses(Endpoint.AdditionalAddress.newBuilder()
                          .setAddress(Address.newBuilder()
                              .setSocketAddress(
                                  SocketAddress.newBuilder()
                                      .setAddress(v6Address).setPortValue(port)))))
                  .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY)
                  .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))
              .build();

      StructOrError<LocalityLbEndpoints> struct =
          XdsEndpointResource.parseLocalityLbEndpoints(proto);
      assertThat(struct.getErrorDetail()).isNull();
      List<java.net.SocketAddress> socketAddressList = Arrays.asList(
          new InetSocketAddress(v4Address, port), new InetSocketAddress(v6Address, port));
      EquivalentAddressGroup expectedEag = new EquivalentAddressGroup(socketAddressList);
      assertThat(struct.getStruct()).isEqualTo(
          LocalityLbEndpoints.create(
              Collections.singletonList(LbEndpoint.create(
                  expectedEag, 20, true, "", ImmutableMap.of())), 100, 1, ImmutableMap.of()));
    } catch (ResourceInvalidException e) {
      throw new RuntimeException(e);
    } finally {
      if (originalDualStackProp != null) {
        System.setProperty(GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS, originalDualStackProp);
      } else {
        System.clearProperty(GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS);
      }
    }
  }

  @Test
  public void parseLocalityLbEndpoints_invalidPriority() throws ResourceInvalidException {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(-1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = XdsEndpointResource.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isEqualTo("negative priority");
  }

  @Test
  public void parseHttpFilter_unsupportedButOptional() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(StringValue.of("unsupported")))
        .build();
    assertThat(XdsListenerResource.parseHttpFilter(httpFilter, filterRegistry, true)).isNull();
  }

  private static class SimpleFilterConfig implements FilterConfig {
    private final Message message;

    public SimpleFilterConfig(Message rawProtoMessage) {
      message = rawProtoMessage;
    }

    public Message getConfig() {
      return message;
    }

    @Override
    public String typeUrl() {
      return null;
    }
  }

  private static class TestFilter implements io.grpc.xds.Filter {

    static final class Provider implements io.grpc.xds.Filter.Provider {
      @Override
      public String[] typeUrls() {
        return new String[]{"test-url"};
      }

      @Override
      public boolean isClientFilter() {
        return true;
      }

      @Override
      public TestFilter newInstance(String name) {
        return new TestFilter();
      }

      @Override
      public ConfigOrError<SimpleFilterConfig> parseFilterConfig(Message rawProtoMessage) {
        return ConfigOrError.fromConfig(new SimpleFilterConfig(rawProtoMessage));
      }

      @Override
      public ConfigOrError<SimpleFilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
        return ConfigOrError.fromConfig(new SimpleFilterConfig(rawProtoMessage));
      }
    }
  }

  @Test
  public void parseHttpFilter_typedStructMigration() {
    filterRegistry.register(new TestFilter.Provider());
    Struct rawStruct = Struct.newBuilder()
        .putFields("name", Value.newBuilder().setStringValue("default").build())
        .build();
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(
            com.github.udpa.udpa.type.v1.TypedStruct.newBuilder()
                .setTypeUrl("test-url")
                .setValue(rawStruct)
        .build())).build();
    FilterConfig config = XdsListenerResource.parseHttpFilter(httpFilter, filterRegistry,
        true).getStruct();
    assertThat(((SimpleFilterConfig)config).getConfig()).isEqualTo(rawStruct);

    HttpFilter httpFilterNewTypeStruct = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(
            TypedStruct.newBuilder()
                .setTypeUrl("test-url")
                .setValue(rawStruct)
                .build())).build();
    config = XdsListenerResource.parseHttpFilter(httpFilterNewTypeStruct, filterRegistry,
        true).getStruct();
    assertThat(((SimpleFilterConfig)config).getConfig()).isEqualTo(rawStruct);
  }

  @Test
  public void parseOverrideHttpFilter_typedStructMigration() {
    filterRegistry.register(new TestFilter.Provider());
    Struct rawStruct0 = Struct.newBuilder()
        .putFields("name", Value.newBuilder().setStringValue("default0").build())
        .build();
    Struct rawStruct1 = Struct.newBuilder()
        .putFields("name", Value.newBuilder().setStringValue("default1").build())
        .build();
    Map<String, Any> rawFilterMap = ImmutableMap.of(
        "struct-0", Any.pack(
            com.github.udpa.udpa.type.v1.TypedStruct.newBuilder()
                .setTypeUrl("test-url")
                .setValue(rawStruct0)
                .build()),
          "struct-1", Any.pack(
              TypedStruct.newBuilder()
                  .setTypeUrl("test-url")
                  .setValue(rawStruct1)
                  .build())
    );
    Map<String, FilterConfig> map = XdsRouteConfigureResource.parseOverrideFilterConfigs(
        rawFilterMap, filterRegistry).getStruct();
    assertThat(((SimpleFilterConfig)map.get("struct-0")).getConfig()).isEqualTo(rawStruct0);
    assertThat(((SimpleFilterConfig)map.get("struct-1")).getConfig()).isEqualTo(rawStruct1);
  }

  @Test
  public void parseHttpFilter_unsupportedAndRequired() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(false)
        .setName("unsupported.filter")
        .setTypedConfig(Any.pack(StringValue.of("string value")))
        .build();
    assertThat(XdsListenerResource.parseHttpFilter(httpFilter, filterRegistry, true)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported "
                + "for client");
  }

  @Test
  public void parseHttpFilter_routerFilterForClient() {
    filterRegistry.register(ROUTER_FILTER_PROVIDER);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance()))
            .build();
    FilterConfig config = XdsListenerResource.parseHttpFilter(
        httpFilter, filterRegistry, true /* isForClient */).getStruct();
    assertThat(config.typeUrl()).isEqualTo(RouterFilter.TYPE_URL);
  }

  @Test
  public void parseHttpFilter_routerFilterForServer() {
    filterRegistry.register(ROUTER_FILTER_PROVIDER);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance()))
            .build();
    FilterConfig config = XdsListenerResource.parseHttpFilter(
        httpFilter, filterRegistry, false /* isForClient */).getStruct();
    assertThat(config.typeUrl()).isEqualTo(RouterFilter.TYPE_URL);
  }

  @Test
  public void parseHttpFilter_faultConfigForClient() {
    filterRegistry.register(FAULT_FILTER_PROVIDER);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.fault")
            .setTypedConfig(
                Any.pack(
                    HTTPFault.newBuilder()
                        .setDelay(
                            FaultDelay.newBuilder()
                                .setFixedDelay(Durations.fromNanos(1234L)))
                        .setAbort(
                            FaultAbort.newBuilder()
                                .setHttpStatus(300)
                                .setPercentage(
                                    FractionalPercent.newBuilder()
                                        .setNumerator(10)
                                        .setDenominator(DenominatorType.HUNDRED)))
                        .build()))
            .build();
    FilterConfig config = XdsListenerResource.parseHttpFilter(
        httpFilter, filterRegistry, true /* isForClient */).getStruct();
    assertThat(config).isInstanceOf(FaultConfig.class);
  }

  @Test
  public void parseHttpFilter_faultConfigUnsupportedForServer() {
    filterRegistry.register(FAULT_FILTER_PROVIDER);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.fault")
            .setTypedConfig(
                Any.pack(
                    HTTPFault.newBuilder()
                        .setDelay(
                            FaultDelay.newBuilder()
                                .setFixedDelay(Durations.fromNanos(1234L)))
                        .setAbort(
                            FaultAbort.newBuilder()
                                .setHttpStatus(300)
                                .setPercentage(
                                    FractionalPercent.newBuilder()
                                        .setNumerator(10)
                                        .setDenominator(DenominatorType.HUNDRED)))
                        .build()))
            .build();
    StructOrError<FilterConfig> config =
        XdsListenerResource.parseHttpFilter(httpFilter, filterRegistry, false /* isForClient */);
    assertThat(config.getErrorDetail()).isEqualTo(
        "HttpFilter [envoy.fault](" + FaultFilter.TYPE_URL + ") is required but "
            + "unsupported for server");
  }

  @Test
  public void parseHttpFilter_rbacConfigForServer() {
    filterRegistry.register(RBAC_FILTER_PROVIDER);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.auth")
            .setTypedConfig(
                Any.pack(
                    io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                        .setRules(
                            RBAC.newBuilder()
                                .setAction(Action.ALLOW)
                                .putPolicies(
                                    "allow-all",
                                    Policy.newBuilder()
                                        .addPrincipals(Principal.newBuilder().setAny(true))
                                        .addPermissions(Permission.newBuilder().setAny(true))
                                        .build())
                                .build())
                        .build()))
            .build();
    FilterConfig config = XdsListenerResource.parseHttpFilter(
        httpFilter, filterRegistry, false /* isForClient */).getStruct();
    assertThat(config).isInstanceOf(RbacConfig.class);
  }

  @Test
  public void parseHttpFilter_rbacConfigUnsupportedForClient() {
    filterRegistry.register(RBAC_FILTER_PROVIDER);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.auth")
            .setTypedConfig(
                Any.pack(
                    io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                        .setRules(
                            RBAC.newBuilder()
                                .setAction(Action.ALLOW)
                                .putPolicies(
                                    "allow-all",
                                    Policy.newBuilder()
                                        .addPrincipals(Principal.newBuilder().setAny(true))
                                        .addPermissions(Permission.newBuilder().setAny(true))
                                        .build())
                                .build())
                        .build()))
            .build();
    StructOrError<FilterConfig> config =
        XdsListenerResource.parseHttpFilter(httpFilter, filterRegistry, true /* isForClient */);
    assertThat(config.getErrorDetail()).isEqualTo(
        "HttpFilter [envoy.auth](" + RbacFilter.TYPE_URL + ") is required but "
            + "unsupported for client");
  }

  @Test
  public void parseOverrideRbacFilterConfig() {
    filterRegistry.register(RBAC_FILTER_PROVIDER);
    RBACPerRoute rbacPerRoute =
        RBACPerRoute.newBuilder()
            .setRbac(
                io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                    .setRules(
                        RBAC.newBuilder()
                            .setAction(Action.ALLOW)
                            .putPolicies(
                                "allow-all",
                                Policy.newBuilder()
                                    .addPrincipals(Principal.newBuilder().setAny(true))
                                    .addPermissions(Permission.newBuilder().setAny(true))
                            .build())))
            .build();
    Map<String, Any> configOverrides = ImmutableMap.of("envoy.auth", Any.pack(rbacPerRoute));
    Map<String, FilterConfig> parsedConfigs =
        XdsRouteConfigureResource.parseOverrideFilterConfigs(configOverrides, filterRegistry)
            .getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.auth");
    assertThat(parsedConfigs.get("envoy.auth")).isInstanceOf(RbacConfig.class);
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedButOptional() {
    filterRegistry.register(FAULT_FILTER_PROVIDER);
    HTTPFault httpFault = HTTPFault.newBuilder()
        .setDelay(FaultDelay.newBuilder().setFixedDelay(Durations.fromNanos(3000)))
        .build();
    Map<String, Any> configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(io.envoyproxy.envoy.config.route.v3.FilterConfig.newBuilder()
            .setIsOptional(true).setConfig(Any.pack(StringValue.of("string value")))
            .build()));
    Map<String, FilterConfig> parsedConfigs =
        XdsRouteConfigureResource.parseOverrideFilterConfigs(configOverrides, filterRegistry)
            .getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.fault");
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedAndRequired() {
    filterRegistry.register(FAULT_FILTER_PROVIDER);
    HTTPFault httpFault = HTTPFault.newBuilder()
        .setDelay(FaultDelay.newBuilder().setFixedDelay(Durations.fromNanos(3000)))
        .build();
    Map<String, Any> configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(io.envoyproxy.envoy.config.route.v3.FilterConfig.newBuilder()
            .setIsOptional(false).setConfig(Any.pack(StringValue.of("string value")))
            .build()));
    assertThat(XdsRouteConfigureResource.parseOverrideFilterConfigs(configOverrides, filterRegistry)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported");

    configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(StringValue.of("string value")));
    assertThat(XdsRouteConfigureResource.parseOverrideFilterConfigs(configOverrides, filterRegistry)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported");
  }

  @Test
  public void parseHttpConnectionManager_xffNumTrustedHopsUnsupported()
      throws ResourceInvalidException {
    @SuppressWarnings("deprecation")
    HttpConnectionManager hcm = HttpConnectionManager.newBuilder().setXffNumTrustedHops(2).build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class,
        () -> XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("HttpConnectionManager with xff_num_trusted_hops unsupported");
  }

  @Test
  public void parseHttpConnectionManager_OriginalIpDetectionExtensionsMustEmpty()
      throws ResourceInvalidException {
    @SuppressWarnings("deprecation")
    HttpConnectionManager hcm = HttpConnectionManager.newBuilder()
        .addOriginalIpDetectionExtensions(TypedExtensionConfig.newBuilder().build())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry, false, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("HttpConnectionManager with original_ip_detection_extensions unsupported");
  }

  @Test
  public void parseHttpConnectionManager_missingRdsAndInlinedRouteConfiguration()
      throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setCommonHttpProtocolOptions(
                HttpProtocolOptions.newBuilder()
                    .setMaxStreamDuration(Durations.fromNanos(1000L)))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("HttpConnectionManager neither has inlined route_config nor RDS");
  }

  @Test
  public void parseHttpConnectionManager_duplicateHttpFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                        Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("HttpConnectionManager contains duplicate HttpFilter: envoy.filter.foo");
  }

  @Test
  public void parseHttpConnectionManager_lastNotTerminal() throws ResourceInvalidException {
    filterRegistry.register(FAULT_FILTER_PROVIDER);
    HttpConnectionManager hcm =
          HttpConnectionManager.newBuilder()
              .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
              .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.bar").setIsOptional(true)
                    .setTypedConfig(Any.pack(HTTPFault.newBuilder().build())))
                    .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("The last HttpFilter must be a terminal filter: envoy.filter.bar");
  }

  @Test
  public void parseHttpConnectionManager_terminalNotLast() throws ResourceInvalidException {
    filterRegistry.register(ROUTER_FILTER_PROVIDER);
    HttpConnectionManager hcm =
            HttpConnectionManager.newBuilder()
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
                    .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("A terminal HttpFilter must be the last filter: terminal");
  }

  @Test
  public void parseHttpConnectionManager_unknownFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
            HttpConnectionManager.newBuilder()
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
                    .addHttpFilters(
                            HttpFilter.newBuilder().setName("envoy.filter.bar").setIsOptional(true))
                    .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("The last HttpFilter must be a terminal filter: envoy.filter.bar");
  }

  @Test
  public void parseHttpConnectionManager_emptyFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
            HttpConnectionManager.newBuilder()
                    .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("Missing HttpFilter in HttpConnectionManager.");
  }

  @Test
  public void parseHttpConnectionManager_clusterSpecifierPlugin() throws Exception {
    XdsRouteConfigureResource.enableRouteLookup = true;
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig).build();
    TypedExtensionConfig typedExtensionConfig = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier))
        .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("rls-plugin-1"))
            .build();
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig)
                            .build())
                    .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                        .setName("virtual-host-1")
                        .addRoutes(route)))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();

    io.grpc.xds.HttpConnectionManager parsedHcm = XdsListenerResource.parseHttpConnectionManager(
        hcm, filterRegistry,
        true /* does not matter */, getXdsResourceTypeArgs(true));

    VirtualHost virtualHost = Iterables.getOnlyElement(parsedHcm.virtualHosts());
    Route parsedRoute = Iterables.getOnlyElement(virtualHost.routes());
    NamedPluginConfig namedPluginConfig =
        parsedRoute.routeAction().namedClusterSpecifierPluginConfig();
    assertThat(namedPluginConfig.name()).isEqualTo("rls-plugin-1");
    assertThat(namedPluginConfig.config()).isInstanceOf(RlsPluginConfig.class);
  }

  @Test
  public void parseHttpConnectionManager_duplicatePluginName() throws Exception {
    XdsRouteConfigureResource.enableRouteLookup = true;
    RouteLookupConfig routeLookupConfig1 = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier1 =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig1).build();
    RouteLookupConfig routeLookupConfig2 = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service3"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier2 =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig2).build();
    TypedExtensionConfig typedExtensionConfig = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier1))
        .build();
    TypedExtensionConfig typedExtensionConfig2 = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier2))
        .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("rls-plugin-1"))
            .build();
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig)
                            .build())
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig2)
                            .build())
                    .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                        .setName("virtual-host-1")
                        .addRoutes(route)))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();

    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("Multiple ClusterSpecifierPlugins with the same name: rls-plugin-1");
  }

  @Test
  public void parseHttpConnectionManager_pluginNameNotFound() throws Exception {
    XdsRouteConfigureResource.enableRouteLookup = true;
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    RouteLookupClusterSpecifier specifier =
        RouteLookupClusterSpecifier.newBuilder().setRouteLookupConfig(routeLookupConfig).build();
    TypedExtensionConfig typedExtensionConfig = TypedExtensionConfig.newBuilder()
        .setName("rls-plugin-1")
        .setTypedConfig(Any.pack(specifier))
        .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("invalid-plugin-name"))
            .build();
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .addClusterSpecifierPlugins(
                        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
                            .setExtension(typedExtensionConfig)
                            .build())
                    .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                        .setName("virtual-host-1")
                        .addRoutes(route)))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();

    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .contains("ClusterSpecifierPlugin for [invalid-plugin-name] not found");
  }


  @Test
  public void parseHttpConnectionManager_optionalPlugin() throws ResourceInvalidException {
    XdsRouteConfigureResource.enableRouteLookup = true;

    // RLS Plugin, and a route to it.
    RouteLookupConfig routeLookupConfig = RouteLookupConfig.newBuilder()
        .addGrpcKeybuilders(
            GrpcKeyBuilder.newBuilder()
                .addNames(Name.newBuilder().setService("service1"))
                .addNames(Name.newBuilder().setService("service2"))
                .addHeaders(
                    NameMatcher.newBuilder().setKey("key1").addNames("v1").setRequiredMatch(true)))
        .setLookupService("rls-cbt.googleapis.com")
        .setLookupServiceTimeout(Durations.fromMillis(1234))
        .setCacheSizeBytes(5000)
        .addValidTargets("valid-target")
        .build();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin rlsPlugin =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(
                TypedExtensionConfig.newBuilder()
                    .setName("rls-plugin-1")
                    .setTypedConfig(Any.pack(
                        RouteLookupClusterSpecifier.newBuilder()
                            .setRouteLookupConfig(routeLookupConfig)
                            .build())))
            .build();
    io.envoyproxy.envoy.config.route.v3.Route rlsRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("rls-route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("rls-plugin-1"))
            .build();

    // Unknown optional plugin, and a route to it.
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin optionalPlugin =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setIsOptional(true)
            .setExtension(
                TypedExtensionConfig.newBuilder()
                    .setName("optional-plugin-1")
                    .setTypedConfig(Any.pack(StringValue.of("unregistered")))
                    .build())
            .build();
    io.envoyproxy.envoy.config.route.v3.Route optionalRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("optional-route-1")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix(""))
            .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                .setClusterSpecifierPlugin("optional-plugin-1"))
            .build();


    // Build and parse the route.
    RouteConfiguration routeConfig = RouteConfiguration.newBuilder()
        .addClusterSpecifierPlugins(rlsPlugin)
        .addClusterSpecifierPlugins(optionalPlugin)
        .addVirtualHosts(
            io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
                .setName("virtual-host-1")
                .addRoutes(rlsRoute)
                .addRoutes(optionalRoute))
        .build();
    io.grpc.xds.HttpConnectionManager parsedHcm = XdsListenerResource.parseHttpConnectionManager(
        HttpConnectionManager.newBuilder().setRouteConfig(routeConfig)
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build(), filterRegistry,
        true /* does not matter */, getXdsResourceTypeArgs(true));

    // Verify that the only route left is the one with the registered RLS plugin `rls-plugin-1`,
    // while the route with unregistered optional `optional-plugin-`1 has been skipped.
    VirtualHost virtualHost = Iterables.getOnlyElement(parsedHcm.virtualHosts());
    Route parsedRoute = Iterables.getOnlyElement(virtualHost.routes());
    NamedPluginConfig namedPluginConfig =
        parsedRoute.routeAction().namedClusterSpecifierPluginConfig();
    assertThat(namedPluginConfig.name()).isEqualTo("rls-plugin-1");
    assertThat(namedPluginConfig.config()).isInstanceOf(RlsPluginConfig.class);
  }

  @Test
  public void parseHttpConnectionManager_validateRdsConfigSource() throws Exception {
    XdsRouteConfigureResource.enableRouteLookup = true;

    HttpConnectionManager hcm1 =
        HttpConnectionManager.newBuilder()
            .setRds(Rds.newBuilder()
                .setRouteConfigName("rds-config-foo")
                .setConfigSource(
                    ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance())))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();
    XdsListenerResource.parseHttpConnectionManager(
        hcm1, filterRegistry,
        true /* does not matter */, getXdsResourceTypeArgs(true));

    HttpConnectionManager hcm2 =
        HttpConnectionManager.newBuilder()
            .setRds(Rds.newBuilder()
                .setRouteConfigName("rds-config-foo")
                .setConfigSource(
                    ConfigSource.newBuilder().setSelf(SelfConfigSource.getDefaultInstance())))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();
    XdsListenerResource.parseHttpConnectionManager(
        hcm2, filterRegistry,
        true /* does not matter */, getXdsResourceTypeArgs(true));

    HttpConnectionManager hcm3 =
        HttpConnectionManager.newBuilder()
            .setRds(Rds.newBuilder()
                .setRouteConfigName("rds-config-foo")
                .setConfigSource(
                    ConfigSource.newBuilder()
                        .setPathConfigSource(PathConfigSource.newBuilder().setPath("foo-path"))))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("terminal").setTypedConfig(
                    Any.pack(Router.newBuilder().build())).setIsOptional(true))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseHttpConnectionManager(
            hcm3, filterRegistry,
            true /* does not matter */, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo(
        "HttpConnectionManager contains invalid RDS: must specify ADS or self ConfigSource");
  }

  @Test
  public void parseClusterSpecifierPlugin_typedStructInTypedExtension() throws Exception {
    class TestPluginConfig implements PluginConfig {
      @Override
      public String typeUrl() {
        return "type.googleapis.com/google.protobuf.Empty";
      }
    }

    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    registry.register(new ClusterSpecifierPlugin() {
      @Override
      public String[] typeUrls() {
        return new String[] {
            "type.googleapis.com/google.protobuf.Empty",
        };
      }

      @Override
      public ConfigOrError<? extends PluginConfig> parsePlugin(Message rawProtoMessage) {
        return ConfigOrError.fromConfig(new TestPluginConfig());
      }
    });

    TypedStruct typedStruct = TypedStruct.newBuilder()
        .setTypeUrl("type.googleapis.com/google.protobuf.Empty")
        .setValue(Struct.newBuilder())
        .build();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(typedStruct)))
            .build();

    PluginConfig pluginConfig = XdsRouteConfigureResource
        .parseClusterSpecifierPlugin(pluginProto, registry);
    assertThat(pluginConfig).isInstanceOf(TestPluginConfig.class);
  }

  @Test
  public void parseClusterSpecifierPlugin_v3TypedStructInTypedExtension() throws Exception {
    class TestPluginConfig implements PluginConfig {
      @Override
      public String typeUrl() {
        return "type.googleapis.com/google.protobuf.Empty";
      }
    }

    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    registry.register(new ClusterSpecifierPlugin() {
      @Override
      public String[] typeUrls() {
        return new String[] {
            "type.googleapis.com/google.protobuf.Empty",
        };
      }

      @Override
      public ConfigOrError<? extends PluginConfig> parsePlugin(Message rawProtoMessage) {
        return ConfigOrError.fromConfig(new TestPluginConfig());
      }
    });

    com.github.xds.type.v3.TypedStruct typedStruct = com.github.xds.type.v3.TypedStruct.newBuilder()
        .setTypeUrl("type.googleapis.com/google.protobuf.Empty")
        .setValue(Struct.newBuilder())
        .build();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(typedStruct)))
            .build();

    PluginConfig pluginConfig = XdsRouteConfigureResource
        .parseClusterSpecifierPlugin(pluginProto, registry);
    assertThat(pluginConfig).isInstanceOf(TestPluginConfig.class);
  }

  @Test
  public void parseClusterSpecifierPlugin_unregisteredPlugin() throws Exception {
    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(StringValue.of("unregistered"))))
            .build();

    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsRouteConfigureResource.parseClusterSpecifierPlugin(pluginProto, registry));
    assertThat(e).hasMessageThat().isEqualTo(
        "Unsupported ClusterSpecifierPlugin type: type.googleapis.com/google.protobuf.StringValue");
  }

  @Test
  public void parseClusterSpecifierPlugin_unregisteredPlugin_optional()
      throws ResourceInvalidException {
    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();
    io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin pluginProto =
        io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
            .setExtension(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(StringValue.of("unregistered"))))
            .setIsOptional(true)
            .build();

    PluginConfig pluginConfig = XdsRouteConfigureResource
        .parseClusterSpecifierPlugin(pluginProto, registry);
    assertThat(pluginConfig).isNull();
  }

  @Test
  public void parseClusterSpecifierPlugin_brokenPlugin() {
    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();

    Any failingAny = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/xds.type.v3.TypedStruct")
        .setValue(ByteString.copyFromUtf8("fail"))
        .build();

    TypedExtensionConfig brokenPlugin = TypedExtensionConfig.newBuilder()
          .setName("bad-plugin-1")
          .setTypedConfig(failingAny)
          .build();

    try {
      XdsRouteConfigureResource.parseClusterSpecifierPlugin(
          io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
              .setExtension(brokenPlugin)
              .build(),
          registry);
      fail("Expected ResourceInvalidException");
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat()
          .startsWith("ClusterSpecifierPlugin [bad-plugin-1] contains invalid proto");
    }
  }

  @Test
  public void parseClusterSpecifierPlugin_brokenPlugin_optional() {
    ClusterSpecifierPluginRegistry registry = ClusterSpecifierPluginRegistry.newRegistry();

    Any failingAny = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/xds.type.v3.TypedStruct")
        .setValue(ByteString.copyFromUtf8("fail"))
        .build();

    TypedExtensionConfig brokenPlugin = TypedExtensionConfig.newBuilder()
          .setName("bad-plugin-1")
          .setTypedConfig(failingAny)
          .build();

    // Despite being optional, still should fail.
    try {
      XdsRouteConfigureResource.parseClusterSpecifierPlugin(
          io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin.newBuilder()
              .setIsOptional(true)
              .setExtension(brokenPlugin)
              .build(),
          registry);
      fail("Expected ResourceInvalidException");
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat()
          .startsWith("ClusterSpecifierPlugin [bad-plugin-1] contains invalid proto");
    }
  }

  @Test
  public void parseCluster_ringHashLbPolicy_defaultLbConfig() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .build();

    CdsUpdate update = XdsClusterResource.processCluster(
        cluster, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(update.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("ring_hash_experimental");
  }

  @Test
  public void parseCluster_leastRequestLbPolicy_defaultLbConfig() throws ResourceInvalidException {
    XdsClusterResource.enableLeastRequest = true;
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.LEAST_REQUEST)
        .build();

    CdsUpdate update = XdsClusterResource.processCluster(
        cluster, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(update.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("least_request_experimental");
  }

  @Test
  public void parseCluster_WrrLbPolicy_defaultLbConfig() throws ResourceInvalidException {
    LoadBalancingPolicy wrrConfig =
        LoadBalancingPolicy.newBuilder().addPolicies(
                LoadBalancingPolicy.Policy.newBuilder()
                    .setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
                        .setName("backend")
                        .setTypedConfig(
                            Any.pack(ClientSideWeightedRoundRobin.newBuilder()
                                .setBlackoutPeriod(Duration.newBuilder().setSeconds(17).build())
                                .setEnableOobLoadReport(
                                    BoolValue.newBuilder().setValue(true).build())
                                .setErrorUtilizationPenalty(
                                    FloatValue.newBuilder().setValue(1.75F).build())
                                .build()))
                        .build())
                    .build())
            .build();

    Cluster cluster = Cluster.newBuilder()
            .setName("cluster-foo.googleapis.com")
            .setType(DiscoveryType.EDS)
            .setEdsClusterConfig(
                EdsClusterConfig.newBuilder()
                    .setEdsConfig(
                        ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance()))
                            .setServiceName("service-foo.googleapis.com"))
            .setLoadBalancingPolicy(
                LoadBalancingPolicy.newBuilder().addPolicies(
                    LoadBalancingPolicy.Policy.newBuilder()
                        .setTypedExtensionConfig(
                            TypedExtensionConfig.newBuilder()
                                .setTypedConfig(
                                    Any.pack(WrrLocality.newBuilder()
                                        .setEndpointPickingPolicy(wrrConfig)
                                        .build()))
                                .build())
                        .build())
                   .build())
              .build();
    CdsUpdate update = XdsClusterResource.processCluster(
            cluster, null, LRS_SERVER_INFO,
            LoadBalancerRegistry.getDefaultRegistry());
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(update.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
            JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("weighted_round_robin");
    WeightedRoundRobinLoadBalancerConfig result = (WeightedRoundRobinLoadBalancerConfig)
        new WeightedRoundRobinLoadBalancerProvider().parseLoadBalancingPolicyConfig(
        childConfigs.get(0).getRawConfigValue()).getConfig();
    assertThat(result.blackoutPeriodNanos).isEqualTo(17_000_000_000L);
    assertThat(result.enableOobLoadReport).isTrue();
    assertThat(result.oobReportingPeriodNanos).isEqualTo(10_000_000_000L);
    assertThat(result.weightUpdatePeriodNanos).isEqualTo(1_000_000_000L);
    assertThat(result.weightExpirationPeriodNanos).isEqualTo(180_000_000_000L);
    assertThat(result.errorUtilizationPenalty).isEqualTo(1.75F);
  }

  @Test
  public void parseCluster_transportSocketMatches_exception() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .addTransportSocketMatches(
            Cluster.TransportSocketMatch.newBuilder().setName("match1").build())
        .build();

    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.processCluster(cluster, null, LRS_SERVER_INFO,
            LoadBalancerRegistry.getDefaultRegistry()));
    assertThat(e).hasMessageThat().isEqualTo(
        "Cluster cluster-foo.googleapis.com: transport-socket-matches not supported.");
  }

  @Test
  public void parseCluster_validateEdsSourceConfig() throws ResourceInvalidException {
    Cluster cluster1 = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .build();
    XdsClusterResource.processCluster(cluster1, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());

    Cluster cluster2 = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setSelf(SelfConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .build();
    XdsClusterResource.processCluster(cluster2, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());

    Cluster cluster3 = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setPathConfigSource(PathConfigSource.newBuilder().setPath("foo-path")))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .build();

    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.processCluster(cluster3, null, LRS_SERVER_INFO,
            LoadBalancerRegistry.getDefaultRegistry()));
    assertThat(e).hasMessageThat().isEqualTo(
        "Cluster cluster-foo.googleapis.com: field eds_cluster_config must be set to indicate to"
            + " use EDS over ADS or self ConfigSource");
  }

  @Test
  public void processCluster_parsesMetadata()
      throws ResourceInvalidException, InvalidProtocolBufferException {
    MetadataRegistry metadataRegistry = MetadataRegistry.getInstance();

    MetadataValueParser testParser =
        new MetadataValueParser() {
          @Override
          public String getTypeUrl() {
            return "type.googleapis.com/test.Type";
          }

          @Override
          public Object parse(Any value) {
            assertThat(value.getValue().toStringUtf8()).isEqualTo("test");
            return value.getValue().toStringUtf8() + "_processed";
          }
        };
    metadataRegistry.registerParser(testParser);

    Any typedFilterMetadata = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/test.Type")
        .setValue(ByteString.copyFromUtf8("test"))
        .build();

    Struct filterMetadata = Struct.newBuilder()
        .putFields("key1", Value.newBuilder().setStringValue("value1").build())
        .putFields("key2", Value.newBuilder().setNumberValue(42).build())
        .build();

    Metadata metadata = Metadata.newBuilder()
        .putTypedFilterMetadata("TYPED_FILTER_METADATA", typedFilterMetadata)
        .putFilterMetadata("FILTER_METADATA", filterMetadata)
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .setMetadata(metadata)
        .build();

    CdsUpdate update = XdsClusterResource.processCluster(
        cluster, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());

    ImmutableMap<String, Object> expectedParsedMetadata = ImmutableMap.of(
        "TYPED_FILTER_METADATA", "test_processed",
        "FILTER_METADATA", ImmutableMap.of(
            "key1", "value1",
            "key2", 42.0));
    assertThat(update.parsedMetadata()).isEqualTo(expectedParsedMetadata);
    metadataRegistry.removeParser(testParser);
  }

  @Test
  public void processCluster_parsesAudienceMetadata() throws Exception {
    FilterRegistry.isEnabledGcpAuthnFilter = true;
    MetadataRegistry.getInstance();

    Audience audience = Audience.newBuilder()
        .setUrl("https://example.com")
        .build();

    Any audienceMetadata = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.gcp_authn.v3.Audience")
        .setValue(audience.toByteString())
        .build();

    Struct filterMetadata = Struct.newBuilder()
        .putFields("key1", Value.newBuilder().setStringValue("value1").build())
        .putFields("key2", Value.newBuilder().setNumberValue(42).build())
        .build();

    Metadata metadata = Metadata.newBuilder()
        .putTypedFilterMetadata("AUDIENCE_METADATA", audienceMetadata)
        .putFilterMetadata("FILTER_METADATA", filterMetadata)
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .setMetadata(metadata)
        .build();

    CdsUpdate update = XdsClusterResource.processCluster(
        cluster, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());

    ImmutableMap<String, Object> expectedParsedMetadata = ImmutableMap.of(
        "AUDIENCE_METADATA", "https://example.com",
        "FILTER_METADATA", ImmutableMap.of(
            "key1", "value1",
            "key2", 42.0));
    try {
      assertThat(update.parsedMetadata().get("FILTER_METADATA"))
          .isEqualTo(expectedParsedMetadata.get("FILTER_METADATA"));
      assertThat(update.parsedMetadata().get("AUDIENCE_METADATA"))
          .isInstanceOf(AudienceWrapper.class);
    } finally {
      FilterRegistry.isEnabledGcpAuthnFilter = false;
    }
  }

  @Test
  public void processCluster_parsesAddressMetadata() throws Exception {

    // Create an Address message
    Address address = Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder()
            .setAddress("192.168.1.1")
            .setPortValue(8080)
            .build())
        .build();

    // Wrap the Address in Any
    Any addressMetadata = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/envoy.config.core.v3.Address")
        .setValue(address.toByteString())
        .build();

    Struct filterMetadata = Struct.newBuilder()
        .putFields("key1", Value.newBuilder().setStringValue("value1").build())
        .putFields("key2", Value.newBuilder().setNumberValue(42).build())
        .build();

    Metadata metadata = Metadata.newBuilder()
        .putTypedFilterMetadata("ADDRESS_METADATA", addressMetadata)
        .putFilterMetadata("FILTER_METADATA", filterMetadata)
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .setMetadata(metadata)
        .build();

    CdsUpdate update = XdsClusterResource.processCluster(
        cluster, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());

    ImmutableMap<String, Object> expectedParsedMetadata = ImmutableMap.of(
        "ADDRESS_METADATA", new InetSocketAddress("192.168.1.1", 8080),
        "FILTER_METADATA", ImmutableMap.of(
            "key1", "value1",
            "key2", 42.0));

    assertThat(update.parsedMetadata()).isEqualTo(expectedParsedMetadata);
  }

  @Test
  public void processCluster_metadataKeyCollision_resolvesToTypedMetadata() throws Exception {
    MetadataRegistry metadataRegistry = MetadataRegistry.getInstance();

    MetadataValueParser testParser =
        new MetadataValueParser() {
          @Override
          public String getTypeUrl() {
            return "type.googleapis.com/test.Type";
          }

          @Override
          public Object parse(Any value) {
            return "typedMetadataValue";
          }
        };
    metadataRegistry.registerParser(testParser);

    Any typedFilterMetadata = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/test.Type")
        .setValue(ByteString.copyFromUtf8("test"))
        .build();

    Struct filterMetadata = Struct.newBuilder()
        .putFields("key1", Value.newBuilder().setStringValue("filterMetadataValue").build())
        .build();

    Metadata metadata = Metadata.newBuilder()
        .putTypedFilterMetadata("key1", typedFilterMetadata)
        .putFilterMetadata("key1", filterMetadata)
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .setMetadata(metadata)
        .build();

    CdsUpdate update = XdsClusterResource.processCluster(
        cluster, null, LRS_SERVER_INFO,
        LoadBalancerRegistry.getDefaultRegistry());

    ImmutableMap<String, Object> expectedParsedMetadata = ImmutableMap.of(
        "key1", "typedMetadataValue");
    assertThat(update.parsedMetadata()).isEqualTo(expectedParsedMetadata);
    metadataRegistry.removeParser(testParser);
  }

  @Test
  public void parseNonAggregateCluster_withHttp11ProxyTransportSocket() throws Exception {
    XdsClusterResource.isEnabledXdsHttpConnect = true;

    Http11ProxyUpstreamTransport http11ProxyUpstreamTransport =
        Http11ProxyUpstreamTransport.newBuilder()
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .build();

    TransportSocket transportSocket = TransportSocket.newBuilder()
        .setName(TRANSPORT_SOCKET_NAME_HTTP11_PROXY)
        .setTypedConfig(Any.pack(http11ProxyUpstreamTransport))
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-http11-proxy.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-http11-proxy.googleapis.com"))
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .setTransportSocket(transportSocket)
        .build();

    CdsUpdate result =
        XdsClusterResource.processCluster(cluster, null, LRS_SERVER_INFO,
            LoadBalancerRegistry.getDefaultRegistry());

    assertThat(result).isNotNull();
    assertThat(result.isHttp11ProxyAvailable()).isTrue();
  }

  @Test
  public void parseServerSideListener_invalidTrafficDirection() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.OUTBOUND)
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(
            listener, null, filterRegistry, null, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("Listener listener1 with invalid traffic direction: OUTBOUND");
  }

  @Test
  public void parseServerSideListener_noTrafficDirection() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .build();
    XdsListenerResource.parseServerSideListener(
        listener, null, filterRegistry, null, getXdsResourceTypeArgs(true));
  }

  @Test
  public void parseServerSideListener_listenerFiltersPresent() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addListenerFilters(ListenerFilter.newBuilder().build())
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(listener, null, filterRegistry, null,
            getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("Listener listener1 cannot have listener_filters");
  }

  @Test
  public void parseServerSideListener_useOriginalDst() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .setUseOriginalDst(BoolValue.of(true))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(listener, null, filterRegistry, null,
            getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("Listener listener1 cannot have use_original_dst set to true");
  }

  @Test
  public void parseServerSideListener_emptyAddress() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .setAddress(Address.newBuilder()
                .setSocketAddress(
                    SocketAddress.newBuilder()))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(
            listener, null, filterRegistry, null, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo("Invalid address: Empty address is not allowed.");
  }

  @Test
  public void parseServerSideListener_namedPort() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .setAddress(Address.newBuilder()
                .setSocketAddress(
                    SocketAddress.newBuilder()
                        .setAddress("172.14.14.5").setNamedPort("")))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(
            listener, null, filterRegistry, null, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo("NAMED_PORT is not supported in gRPC.");
  }

  @Test
  public void parseServerSideListener_nonUniqueFilterChainMatch() throws ResourceInvalidException {
    Filter filter1 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-1").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch1 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(80, 8080))
            .addAllPrefixRanges(Arrays.asList(CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build(),
                CidrRange.newBuilder().setAddressPrefix("10.0.0.0").setPrefixLen(UInt32Value.of(8))
                    .build()))
            .build();
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter-chain-1")
            .setFilterChainMatch(filterChainMatch1)
            .addFilters(filter1)
            .build();
    Filter filter2 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-2").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch2 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(443, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("2001:DB8::8:800:200C:417A")
                    .setPrefixLen(UInt32Value.of(60)).build(),
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build()))
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setName("filter-chain-2")
            .setFilterChainMatch(filterChainMatch2)
            .addFilters(filter2)
            .build();
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain1, filterChain2))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(
            listener, null, filterRegistry, null, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .startsWith("FilterChainMatch must be unique. Found duplicate:");
  }

  @Test
  public void parseServerSideListener_nonUniqueFilterChainMatch_sameFilter()
      throws ResourceInvalidException {
    Filter filter1 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-1").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch1 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(80, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("10.0.0.0").setPrefixLen(UInt32Value.of(8))
                    .build()))
            .build();
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter-chain-1")
            .setFilterChainMatch(filterChainMatch1)
            .addFilters(filter1)
            .build();
    Filter filter2 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-2").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch2 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(443, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build(),
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build()))
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setName("filter-chain-2")
            .setFilterChainMatch(filterChainMatch2)
            .addFilters(filter2)
            .build();
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain1, filterChain2))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(
            listener, null, filterRegistry, null, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .startsWith("FilterChainMatch must be unique. Found duplicate:");
  }

  @Test
  public void parseServerSideListener_uniqueFilterChainMatch() throws ResourceInvalidException {
    Filter filter1 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-1").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch1 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(80, 8080))
            .addAllPrefixRanges(Arrays.asList(CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build(),
                CidrRange.newBuilder().setAddressPrefix("10.0.0.0").setPrefixLen(UInt32Value.of(8))
                    .build()))
            .setSourceType(FilterChainMatch.ConnectionSourceType.EXTERNAL)
            .build();
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter-chain-1")
            .setFilterChainMatch(filterChainMatch1)
            .addFilters(filter1)
            .build();
    Filter filter2 = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-2").setTypedConfig(
            Any.pack(Router.newBuilder().build())).setIsOptional(true).build());
    FilterChainMatch filterChainMatch2 =
        FilterChainMatch.newBuilder()
            .addAllSourcePorts(Arrays.asList(443, 8080))
            .addAllPrefixRanges(Arrays.asList(
                CidrRange.newBuilder().setAddressPrefix("2001:DB8::8:800:200C:417A")
                    .setPrefixLen(UInt32Value.of(60)).build(),
                CidrRange.newBuilder().setAddressPrefix("192.168.0.0")
                    .setPrefixLen(UInt32Value.of(16)).build()))
            .setSourceType(FilterChainMatch.ConnectionSourceType.ANY)
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setName("filter-chain-2")
            .setFilterChainMatch(filterChainMatch2)
            .addFilters(filter2)
            .build();
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain1, filterChain2))
            .build();
    XdsListenerResource.parseServerSideListener(
        listener, null, filterRegistry, null, getXdsResourceTypeArgs(true));
  }

  @Test
  public void parseFilterChain_noHcm() throws ResourceInvalidException {
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseFilterChain(
            filterChain, "filter-chain-foo", null, filterRegistry, null, null,
            getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo(
        "FilterChain filter-chain-foo should contain exact one HttpConnectionManager filter");
  }

  @Test
  public void parseFilterChain_duplicateFilter() throws ResourceInvalidException {
    Filter filter = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-foo").setIsOptional(true).build());
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addAllFilters(Arrays.asList(filter, filter))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseFilterChain(
            filterChain, "filter-chain-foo", null, filterRegistry, null, null,
            getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo(
        "FilterChain filter-chain-foo should contain exact one HttpConnectionManager filter");
  }

  @Test
  public void parseFilterChain_filterMissingTypedConfig() throws ResourceInvalidException {
    Filter filter = Filter.newBuilder().setName("envoy.http_connection_manager").build();
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addFilters(filter)
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseFilterChain(
            filterChain, "filter-chain-foo", null, filterRegistry, null, null,
            getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo(
        "FilterChain filter-chain-foo contains filter envoy.http_connection_manager "
            + "without typed_config");
  }

  @Test
  public void parseFilterChain_unsupportedFilter() throws ResourceInvalidException {
    Filter filter =
        Filter.newBuilder()
            .setName("unsupported")
            .setTypedConfig(Any.newBuilder().setTypeUrl("unsupported-type-url"))
            .build();
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addFilters(filter)
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseFilterChain(
            filterChain, "filter-chain-foo", null, filterRegistry, null, null,
            getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat().isEqualTo(
        "FilterChain filter-chain-foo contains filter unsupported with unsupported "
            + "typed_config type unsupported-type-url");
  }

  @Test
  public void parseFilterChain_noName() throws ResourceInvalidException {
    FilterChain filterChain0 =
        FilterChain.newBuilder()
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-foo")
                    .setIsOptional(true)
                    .setTypedConfig(Any.pack(Router.newBuilder().build()))
                    .build()))
            .build();

    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setFilterChainMatch(
                FilterChainMatch.newBuilder().addAllSourcePorts(Arrays.asList(443, 8080)))
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-bar")
                    .setTypedConfig(Any.pack(Router.newBuilder().build()))
                    .setIsOptional(true)
                    .build()))
            .build();

    Listener listenerProto =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain0, filterChain1))
            .setDefaultFilterChain(filterChain0)
            .build();
    EnvoyServerProtoData.Listener listener = XdsListenerResource.parseServerSideListener(
        listenerProto, null, filterRegistry, null, getXdsResourceTypeArgs(true));

    assertThat(listener.filterChains().get(0).name()).isEqualTo("chain_0");
    assertThat(listener.filterChains().get(1).name()).isEqualTo("chain_1");
    assertThat(listener.defaultFilterChain().name()).isEqualTo("chain_default");
  }

  @Test
  public void parseFilterChain_duplicateName() throws ResourceInvalidException {
    FilterChain filterChain0 =
        FilterChain.newBuilder()
            .setName("filter_chain")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-foo")
                    .setIsOptional(true)
                    .setTypedConfig(Any.pack(Router.newBuilder().build()))
                    .build()))
            .build();

    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setName("filter_chain")
            .setFilterChainMatch(
                FilterChainMatch.newBuilder().addAllSourcePorts(Arrays.asList(443, 8080)))
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-bar")
                    .setTypedConfig(Any.pack(Router.newBuilder().build()))
                    .setIsOptional(true)
                    .build()))
            .build();

    Listener listenerProto =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addAllFilterChains(Arrays.asList(filterChain0, filterChain1))
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsListenerResource.parseServerSideListener(
            listenerProto, null, filterRegistry, null, getXdsResourceTypeArgs(true)));
    assertThat(e).hasMessageThat()
        .isEqualTo("Filter chain names must be unique. Found duplicate: filter_chain");
  }

  @Test
  public void validateCommonTlsContext_tlsParams() {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .setTlsParams(TlsParameters.getDefaultInstance())
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo("common-tls-context with tls_params is not supported");
  }

  @Test
  public void validateCommonTlsContext_customHandshaker() {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .setCustomHandshaker(TypedExtensionConfig.getDefaultInstance())
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo(
        "common-tls-context with custom_handshaker is not supported");
  }

  @Test
  public void validateCommonTlsContext_validationContext() {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .setValidationContext(CertificateValidationContext.getDefaultInstance())
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo(
        "ca_certificate_provider_instance or system_root_certs is required "
            + "in upstream-tls-context");
  }

  @Test
  public void validateCommonTlsContext_validationContextSdsSecretConfig() {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContextSdsSecretConfig(SdsSecretConfig.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo(
        "common-tls-context with validation_context_sds_secret_config is not supported");
  }

  @Test
  public void validateCommonTlsContext_tlsCertificateProviderInstance_isRequiredForServer()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, true));
    assertThat(e).hasMessageThat().isEqualTo(
        "tls_certificate_provider_instance is required in downstream-tls-context");
  }

  @Test
  public void validateCommonTlsContext_tlsNewCertificateProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateProviderInstance(
            CertificateProviderPluginInstance.newBuilder().setInstanceName("name1"))
        .build();
    XdsClusterResource
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), true);
  }

  @Test
  public void validateCommonTlsContext_tlsCertificateProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateProviderInstance(
            CertificateProviderPluginInstance.newBuilder().setInstanceName("name1"))
        .build();
    XdsClusterResource
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), true);
  }

  @Test
  public void validateCommonTlsContext_tlsCertificateProviderInstance_absentInBootstrapFile()
          throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setTlsCertificateProviderInstance(
            CertificateProviderPluginInstance.newBuilder().setInstanceName("bad-name"))
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext,
            ImmutableSet.of("name1", "name2"), true));
    assertThat(e).hasMessageThat().isEqualTo(
        "CertificateProvider instance name 'bad-name' not defined in the bootstrap file.");
  }

  @Test
  public void validateCommonTlsContext_validationContextProviderInstance()
          throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
              .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                .setCaCertificateProviderInstance(CertificateProviderPluginInstance.newBuilder()
                  .setInstanceName("name1"))))
        .build();
    XdsClusterResource
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of("name1", "name2"), false);
  }

  @Test
  public void
      validateCommonTlsContext_combinedValidationContextSystemRootCerts_envVarNotSet_throws() {
    XdsClusterResource.enableSystemRootCerts = false;
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(
                    CertificateValidationContext.newBuilder()
                        .setSystemRootCerts(
                            CertificateValidationContext.SystemRootCerts.newBuilder().build())
                        .build()
                )
                .build())
        .build();
    try {
      XdsClusterResource
          .validateCommonTlsContext(commonTlsContext, ImmutableSet.of(), false);
      fail("Expected exception");
    } catch (ResourceInvalidException ex) {
      assertThat(ex.getMessage()).isEqualTo(
          "ca_certificate_provider_instance or system_root_certs is required in"
              + " upstream-tls-context");
    }
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContextSystemRootCerts()
      throws ResourceInvalidException {
    XdsClusterResource.enableSystemRootCerts = true;
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(
                    CertificateValidationContext.newBuilder()
                        .setSystemRootCerts(
                            CertificateValidationContext.SystemRootCerts.newBuilder().build())
                        .build()
                )
                .build())
        .build();
    XdsClusterResource
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of(), false);
  }

  @Test
  public void validateCommonTlsContext_validationContextSystemRootCerts_envVarNotSet_throws() {
    XdsClusterResource.enableSystemRootCerts = false;
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContext(
            CertificateValidationContext.newBuilder()
                .setSystemRootCerts(
                    CertificateValidationContext.SystemRootCerts.newBuilder().build())
                .build())
        .build();
    try {
      XdsClusterResource
          .validateCommonTlsContext(commonTlsContext, ImmutableSet.of(), false);
      fail("Expected exception");
    } catch (ResourceInvalidException ex) {
      assertThat(ex.getMessage()).isEqualTo(
          "ca_certificate_provider_instance or system_root_certs is required in "
              + "upstream-tls-context");
    }
  }

  @Test
  public void validateCommonTlsContext_validationContextSystemRootCerts()
      throws ResourceInvalidException {
    XdsClusterResource.enableSystemRootCerts = true;
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setValidationContext(
            CertificateValidationContext.newBuilder()
                .setSystemRootCerts(
                    CertificateValidationContext.SystemRootCerts.newBuilder().build())
                .build())
        .build();
    XdsClusterResource
        .validateCommonTlsContext(commonTlsContext, ImmutableSet.of(), false);
  }

  @Test
  public void validateCommonTlsContext_validationContextProviderInstance_absentInBootstrapFile()
          throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
              .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                .setCaCertificateProviderInstance(CertificateProviderPluginInstance.newBuilder()
                  .setInstanceName("bad-name"))))
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext,
            ImmutableSet.of("name1", "name2"), false));
    assertThat(e).hasMessageThat().isEqualTo(
        "ca_certificate_provider_instance name 'bad-name' not defined in the bootstrap file.");
  }


  @Test
  public void validateCommonTlsContext_tlsCertificatesCount() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
            .addTlsCertificates(TlsCertificate.getDefaultInstance())
            .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo("tls_certificate_provider_instance is unset");
  }

  @Test
  public void validateCommonTlsContext_tlsCertificateSdsSecretConfigsCount()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .addTlsCertificateSdsSecretConfigs(SdsSecretConfig.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo(
        "tls_certificate_provider_instance is unset");
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContext_isRequiredForClient()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo(
        "ca_certificate_provider_instance or system_root_certs is required "
            + "in upstream-tls-context");
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContextWithoutCertProviderInstance()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, null, false));
    assertThat(e).hasMessageThat().isEqualTo(
        "ca_certificate_provider_instance or system_root_certs is required in "
            + "upstream-tls-context");
  }

  @Test
  @SuppressWarnings("deprecation")
  public void validateCommonTlsContext_combinedValContextWithDefaultValContextForServer()
      throws ResourceInvalidException, InvalidProtocolBufferException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())
                    .addMatchSubjectAltNames(StringMatcher.newBuilder().setExact("foo.com").build())
                    .build()))
        .setTlsCertificateProviderInstance(
            CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), true));
    assertThat(e).hasMessageThat().isEqualTo(
        "match_subject_alt_names only allowed in upstream_tls_context");
  }

  @Test
  public void validateCommonTlsContext_combinedValContextWithDefaultValContextVerifyCertSpki()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())
                    .addVerifyCertificateSpki("foo")))
        .setTlsCertificateProviderInstance(CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false));
    assertThat(e).hasMessageThat().isEqualTo(
        "verify_certificate_spki in default_validation_context is not supported");
  }

  @Test
  public void validateCommonTlsContext_combinedValContextWithDefaultValContextVerifyCertHash()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())
                    .addVerifyCertificateHash("foo")))
        .setTlsCertificateProviderInstance(CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false));
    assertThat(e).hasMessageThat().isEqualTo(
        "verify_certificate_hash in default_validation_context is not supported");
  }

  @Test
  public void validateCommonTlsContext_combinedValContextDfltValContextRequireSignedCertTimestamp()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())
                    .setRequireSignedCertificateTimestamp(BoolValue.of(true))))
        .setTlsCertificateProviderInstance(
            CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false));
    assertThat(e).hasMessageThat().isEqualTo(
        "require_signed_certificate_timestamp in default_validation_context is not "
            + "supported");
  }

  @Test
  public void validateCommonTlsContext_combinedValidationContextWithDefaultValidationContextCrl()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())
                    .setCrl(DataSource.getDefaultInstance())))
        .setTlsCertificateProviderInstance(CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false));
    assertThat(e).hasMessageThat().isEqualTo("crl in default_validation_context is not supported");
  }

  @Test
  public void validateCommonTlsContext_combinedValContextWithDfltValContextCustomValidatorConfig()
      throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())
                    .setCustomValidatorConfig(TypedExtensionConfig.getDefaultInstance())))
        .setTlsCertificateProviderInstance(CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsClusterResource.validateCommonTlsContext(commonTlsContext, ImmutableSet.of(""), false));
    assertThat(e).hasMessageThat().isEqualTo(
        "custom_validator_config in default_validation_context is not supported");
  }

  @Test
  public void validateDownstreamTlsContext_noCommonTlsContext() throws ResourceInvalidException {
    DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.getDefaultInstance();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsListenerResource.validateDownstreamTlsContext(downstreamTlsContext, null));
    assertThat(e).hasMessageThat().isEqualTo(
        "common-tls-context is required in downstream-tls-context");
  }

  @Test
  public void validateDownstreamTlsContext_hasRequireSni() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())))
        .setTlsCertificateProviderInstance(CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.newBuilder()
        .setCommonTlsContext(commonTlsContext)
        .setRequireSni(BoolValue.of(true))
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsListenerResource.validateDownstreamTlsContext(downstreamTlsContext,
            ImmutableSet.of("")));
    assertThat(e).hasMessageThat().isEqualTo(
        "downstream-tls-context with require-sni is not supported");
  }

  @Test
  public void validateDownstreamTlsContext_hasOcspStaplePolicy() throws ResourceInvalidException {
    CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
        .setCombinedValidationContext(
            CommonTlsContext.CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(CertificateValidationContext.newBuilder()
                    .setCaCertificateProviderInstance(
                        CertificateProviderPluginInstance.getDefaultInstance())))
        .setTlsCertificateProviderInstance(CertificateProviderPluginInstance.getDefaultInstance())
        .build();
    DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.newBuilder()
        .setCommonTlsContext(commonTlsContext)
        .setOcspStaplePolicy(DownstreamTlsContext.OcspStaplePolicy.STRICT_STAPLING)
        .build();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
        XdsListenerResource.validateDownstreamTlsContext(downstreamTlsContext,
            ImmutableSet.of("")));
    assertThat(e).hasMessageThat().isEqualTo(
        "downstream-tls-context with ocsp_staple_policy value STRICT_STAPLING is not supported");
  }

  @Test
  public void validateUpstreamTlsContext_noCommonTlsContext() throws ResourceInvalidException {
    UpstreamTlsContext upstreamTlsContext = UpstreamTlsContext.getDefaultInstance();
    ResourceInvalidException e = assertThrows(ResourceInvalidException.class, () ->
            XdsClusterResource.validateUpstreamTlsContext(upstreamTlsContext, null));
    assertThat(e).hasMessageThat().isEqualTo(
        "common-tls-context is required in upstream-tls-context");
  }

  @Test
  public void validateResourceName() {
    String traditionalResource = "cluster1.google.com";
    assertThat(XdsClient.isResourceNameValid(traditionalResource,
        XdsClusterResource.getInstance().typeUrl()))
        .isTrue();

    String invalidPath = "xdstp:/abc/efg";
    assertThat(XdsClient.isResourceNameValid(invalidPath,
        XdsClusterResource.getInstance().typeUrl())).isFalse();

    String invalidPath2 = "xdstp:///envoy.config.route.v3.RouteConfiguration";
    assertThat(XdsClient.isResourceNameValid(invalidPath2,
        XdsRouteConfigureResource.getInstance().typeUrl())).isFalse();

    String typeMatch = "xdstp:///envoy.config.route.v3.RouteConfiguration/foo/route1";
    assertThat(XdsClient.isResourceNameValid(typeMatch,
        XdsListenerResource.getInstance().typeUrl())).isFalse();
    assertThat(XdsClient.isResourceNameValid(typeMatch,
        XdsRouteConfigureResource.getInstance().typeUrl())).isTrue();
  }

  @Test
  public void canonifyResourceName() {
    String traditionalResource = "cluster1.google.com";
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);
    assertThat(XdsClient.canonifyResourceName(traditionalResource))
        .isEqualTo(traditionalResource);

    String withNoQueries = "xdstp:///envoy.config.route.v3.RouteConfiguration/foo/route1";
    assertThat(XdsClient.canonifyResourceName(withNoQueries)).isEqualTo(withNoQueries);

    String withOneQueries = "xdstp:///envoy.config.route.v3.RouteConfiguration/foo/route1?name=foo";
    assertThat(XdsClient.canonifyResourceName(withOneQueries)).isEqualTo(withOneQueries);

    String withTwoQueries = "xdstp:///envoy.config.route.v3.RouteConfiguration/id/route1?b=1&a=1";
    String expectedCanonifiedName =
        "xdstp:///envoy.config.route.v3.RouteConfiguration/id/route1?a=1&b=1";
    assertThat(XdsClient.canonifyResourceName(withTwoQueries))
        .isEqualTo(expectedCanonifiedName);
  }

  /**
   *  Tests compliance with RFC 3986 section 3.3
   *  https://datatracker.ietf.org/doc/html/rfc3986#section-3.3 .
   */
  @Test
  public void percentEncodePath()  {
    String unreserved = "aAzZ09-._~";
    assertThat(XdsClient.percentEncodePath(unreserved)).isEqualTo(unreserved);

    String subDelims = "!$&'(*+,;/=";
    assertThat(XdsClient.percentEncodePath(subDelims)).isEqualTo(subDelims);

    String colonAndAt = ":@";
    assertThat(XdsClient.percentEncodePath(colonAndAt)).isEqualTo(colonAndAt);

    String needBeEncoded = "?#[]";
    assertThat(XdsClient.percentEncodePath(needBeEncoded)).isEqualTo("%3F%23%5B%5D");

    String ipv4 = "0.0.0.0:8080";
    assertThat(XdsClient.percentEncodePath(ipv4)).isEqualTo(ipv4);

    String ipv6 = "[::1]:8080";
    assertThat(XdsClient.percentEncodePath(ipv6)).isEqualTo("%5B::1%5D:8080");
  }

  private static Filter buildHttpConnectionManagerFilter(HttpFilter... httpFilters) {
    return Filter.newBuilder()
        .setName("envoy.http_connection_manager")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName("route-config.googleapis.com")
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .addAllHttpFilters(Arrays.asList(httpFilters))
                    .build(),
                "type.googleapis.com"))
        .build();
  }

  private XdsResourceType.Args getXdsResourceTypeArgs(boolean isTrustedServer) {
    return new XdsResourceType.Args(
        ServerInfo.create("http://td", "", false, isTrustedServer, false), "1.0", null, null, null, null
    );
  }
}
