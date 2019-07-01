module com.github.gv2011.guava {
  requires org.checkerframework.checker.qual;
//  requires error.prone.annotations;
  requires java.logging;
//  requires jsr305;
  requires j2objc.annotations;
  requires java.compiler;
//  requires jdk.unsupported;
//  requires failureaccess;

  exports com.google.common.collect;
}
