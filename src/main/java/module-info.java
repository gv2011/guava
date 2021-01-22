module com.github.gv2011.guava {
  requires org.slf4j;
  requires java.compiler;
  requires java.base;

  exports com.google.common.collect;
  exports com.google.common.base;
  exports com.google.common.net;
  exports com.google.common.annotations;
}
