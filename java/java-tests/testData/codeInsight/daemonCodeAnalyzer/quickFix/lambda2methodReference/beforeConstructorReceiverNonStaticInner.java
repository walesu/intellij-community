// "Replace lambda with method reference" "true"
class NonStaticInner {
  class Inner {
    Inner() {}
  }

  interface I1 {
    Inner m(NonStaticInner rec);
  }
  static {
    I1 i1 = (rec) -> r<caret>ec.new Inner();
  }
}
