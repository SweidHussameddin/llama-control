package de.sweid.llamactl.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bound to the {@code llama-control:} block in application.yml. */
@Component
@ConfigurationProperties(prefix = "llama-control")
public class LlamaControlProperties {

  private String binary;
  private List<String> modelDirs = List.of();
  private String profilesDir;
  private String defaultServerHost = "127.0.0.1";
  private int defaultServerPort = 8080;
  private String proxyHost = "127.0.0.1";
  private int proxyPort = 8090;

  public String getBinary() {
    return binary;
  }

  public void setBinary(String binary) {
    this.binary = binary;
  }

  public List<String> getModelDirs() {
    return modelDirs;
  }

  public void setModelDirs(List<String> modelDirs) {
    this.modelDirs = modelDirs;
  }

  public String getProfilesDir() {
    return profilesDir;
  }

  public void setProfilesDir(String profilesDir) {
    this.profilesDir = profilesDir;
  }

  public String getDefaultServerHost() {
    return defaultServerHost;
  }

  public void setDefaultServerHost(String defaultServerHost) {
    this.defaultServerHost = defaultServerHost;
  }

  public int getDefaultServerPort() {
    return defaultServerPort;
  }

  public void setDefaultServerPort(int defaultServerPort) {
    this.defaultServerPort = defaultServerPort;
  }

  public String getProxyHost() {
    return proxyHost;
  }

  public void setProxyHost(String proxyHost) {
    this.proxyHost = proxyHost;
  }

  public int getProxyPort() {
    return proxyPort;
  }

  public void setProxyPort(int proxyPort) {
    this.proxyPort = proxyPort;
  }

  /**
   * Returns the OpenAI-compatible base URL clients should connect to (our proxy, not the
   * upstream llama-server).
   *
   * @return e.g. {@code http://127.0.0.1:8090/v1}
   */
  public String getProxyBaseUrl() {
    return "http://" + proxyHost + ":" + proxyPort + "/v1";
  }
}
