object Foo {
  rewrite def foo[X](x: X): Unit = ~fooImpl('(x))
  def fooImpl[X: quoted.Type](x: X): quoted.Expr[Unit] = '()
}
