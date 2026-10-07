# JVM 互操作

Sprig 会先编译成 Java 再运行，所以 JDK 自带的类和 Maven 上的 Java 库都能直接用。这页讲怎么导入、调用 Java 类，以及 Sprig 在和 Java 交接的地方会做哪些检查。

总的原则偏保守：Java 没法保证一个返回值不是 `null`，Sprig 就当它可能是 `null`，唯一的例外是 `toString()`，它总是返回 `String`；反过来，你传给 Java 的参数一律不能是 `null`。

完整规则见 [JVM 互操作参考（英文）](/en/reference/jvm/interop)。想把 Sprig 接进 Gradle、Loom 这类现有构建，见 [Gradle 集成](/guide/gradle)和 [Fabric 模组](/guide/fabric)。

## 导入和调用 Java 类

<<< @/snippets/jvm_interop.spr

```text
9
4
Sprig!
2026
25
```

- `import java.lang.Math as Math` 让你在这个文件里用 `Math` 指代这个类。`java.lang` 下的类也要这样导入，不会自动导入。
- 构造器、静态方法、实例方法和静态字段都能通过这个名字使用。
- 有重载时，Sprig 按参数类型挑选。没有匹配的方法会报 `SPR-JVM-MEMBER`，有好几个同样合适的会报 `SPR-JVM-AMBIGUOUS`。

## Java 返回的值要先判空

Java 方法返回的对象，包括 `String` 和 `Integer` 这类装箱类型，都被当作可能为 `null`，用之前要检查：

<<< @/snippets/guide/jvm_nullable.spr

```text
true
```

返回基本类型（`long`、`int`、`double`、`boolean` 等）的方法不会返回 `null`，结果可以直接用。

反过来，传给 Java 的参数一律不能是 `null`，参数类型是 `Object` 也一样。所以 `T?` 类型的值要先检查，再传给 Java。Sprig 会读取可空性注解：标了 `@NotNull`/`@NonNull`（或者所在类、包标了 `@NullMarked`）的结果就是普通的 `T`，标了 `@Nullable` 的参数接受 `T?` 或 `null`。运行时可见的注解（JSpecify、JSR-305）通过反射读，只保留在 class 文件里的注解（比如 `org.jetbrains.annotations`）直接从 class 文件里读。Minecraft 本身用 JSpecify 标注，每个包都有 `@NullMarked`，所以 `Item.use`、`Component.literal` 拿到的就是普通值，只有标了 `@Nullable` 的成员才是 `T?`。

## 类型对照

| Java | Sprig（基本类型） | Sprig（装箱类型和对象） |
|---|---|---|
| `long` | `Int` | `Int?` |
| `int`、`short`、`byte` | `Int32` | `Int32?` |
| `double` | `Float` | `Float?` |
| `float` | `Float32` | `Float32?` |
| `boolean` | `Bool` | `Bool?` |
| `char` | `String` | `String?` |
| `void` | `Unit` | — |
| `String` 和其他对象类型 | — | 对应的可空类型 `T?` |

有几处要注意：

- **`Int` 传给 `int` 会带检查地收窄。** 把 `Int` 传到 Java 需要 `int` 或 `Integer` 的地方（参数、构造器参数、变长参数元素、普通的字段赋值，或者比较器这类回调的返回值）时，会做范围检查，值装不下就在运行时报错。精确匹配的 `long` 重载仍然优先，所以 `Math.max(a, b)` 还是走 `long` 版本。范围合适的整数字面量也可以传给 `short`、`byte` 参数，但变量不会收窄到这两种类型；`Float` 也不会收窄成 `float`，因为那丢的是精度而不是范围，要用 `toFloat32Exact()`。
- **`char` 参数只接受一个字符的字面量。** `char` 和 `Character` 参数只接受恰好一个 UTF-16 字符的字符串字面量。`"a"` 可以，`"ab"` 不行，`"😀"` 也不行，因为一个 Java `char` 装不下它。Sprig 自己的字符串则按 Unicode 码点计算位置，详见 `sprig help strings`。

## 捕获 Java 异常

Java 的受检异常可以写进 Sprig 的 `throws`，也可以用 `catch` 捕获：

<<< @/snippets/jvm_exceptions.spr

```text
example.com
bad uri
```

捕获到的 Java 异常，不管是打印、拼接还是调用 `toString()`，显示的都是 Java 的格式，类名在前。它的 `message` 是 `String?`，因为 Java 的 `getMessage()` 可能返回 `null`，拼接之前要先检查。Sprig 的 `Error` 只显示消息本身。

## Java 集合和 Sprig 集合

Java 的 `List`、`Map` 不会自动变成 Sprig 的集合，Sprig 的集合也不会被悄悄当作 Java 集合传进去。需要转换时，用 `@std/jvm.spr` 里的函数：

<<< @/snippets/guide/jvm_collections.spr

```text
2
3
2
```

- `list_snapshot` 和 `map_snapshot` 把 Java 集合按顺序复制成只读的 Sprig `List` 和 `Map`。之后 Java 那边怎么改，快照都不受影响：上面 `names` 加了第三个名字，`snapshot` 还是两个。
- `list_copy` 和 `map_copy` 反过来，把 Sprig 集合复制成独立的 Java `ArrayList` 和 `LinkedHashMap`，两边各改各的。
- Java 集合里如果有 `null` 元素、键或值，复制时会报运行时错误，不会让 `null` 混进不允许为空的 Sprig 集合。
- 元素类型要和来源一致，写错了在检查阶段就会报错。
- 这几个函数都声明了 `throws Error`，在函数里调用时记得处理。

## Java 泛型类

导入的 Java 类和泛型方法也可以带具体的类型参数，比如上面的 `ArrayList[String]()`。

- 类型参数会完整保留，`List[Map[String, Int32]]`、`Host.method[String](value)` 这样的写法都可以，经由父类或接口传下来的类型参数也能识别。
- 返回值照样被当作可能为 `null`，所以 `ArrayList[String]` 的 `get` 返回 `String?`。
- 泛型方法的类型参数不会推断，要自己写出来。
- 没有类型参数的原始类型（raw type）不能当作带参数的类型使用。`ArrayList[String]` 可以当 `List[String]` 用，`ArrayList[Int32]` 不行。
- 通配符会保留边界：`List<? extends Number>` 的结果读出来是 `Number?`，`List<? extends Number>` 形参可以接收 `ArrayList[Int]`，而任何会穿过 `? extends` 写入的成员（比如 `add`）都会被拒绝。泛型数组（`T[]`）不支持，用 `sprig api` 查询时会看到对应的原因代码。

## 数组和字节

Java 数组在 Sprig 里是一个整体，不能拆开用。你可以接收它、判空、原样传给另一个需要同类数组的 Java 方法，但不能在 Sprig 里创建、索引、修改或遍历它。Sprig 没有数组语法，这些写法在调用 javac 之前就会被拒绝，报 `SPR-SYNTAX-ERROR` 或 `SPR-TYPE-OPERAND`。重载匹配时，`byte[]`、`int[]`、`String[]`、`Object[]` 会被区分开，数组之间能否互相传递按 JVM 的实际规则判断。

二进制数据最常见的形式是 `byte[]`，可以用 `sprig.runtime.jvm.HostBytes` 来转换：

<<< @/snippets/guide/jvm_bytes.spr

```text
5
5370726967
```

| 方法 | 作用 |
|---|---|
| `HostBytes.utf8(text)` | 字符串转成 UTF-8 字节 |
| `HostBytes.utf8String(bytes)` | UTF-8 字节转回字符串；不是合法的 UTF-8 时报运行时错误 |
| `HostBytes.length(bytes)` | 字节数，返回 `Int` |
| `HostBytes.hex(bytes)` | 转成小写的十六进制字符串 |

转换都要你自己调用，Sprig 不会自动转换。

## 回调和变长参数

Java 方法要的是「函数式接口」（只有一个抽象方法的接口：`Runnable`、`Comparator<T>`、`Consumer<? super T>`、`Predicate<? super T>` 以及 `java.util.function` 里的其他接口）时，可以直接传 Sprig 的函数值。编译器按通常的类型对照从接口方法推出它要的 `fn(...) -> R`，并替你生成 Java 适配代码：

<<< @/snippets/jvm_callables.spr

```text
ada
grace
true
config.json
false
a/b/c
```

- 参数类型必须完全一致；方法返回 `void` 时，lambda 返回什么都可以。`Comparator.compare` 返回 `int`，所以 lambda 返回 `Int32`（`a.compareTo(b)` 正好是）或者 `Int` 都可以；返回 `Int` 时会像传给 `int` 参数一样做运行时范围检查。
- 接口类型参数里的通配符没关系：实现了 `Consumer<String>` 的 lambda 就是一个 `Consumer<? super String>`。
- 类型变量从不推断。`names.forEach` 能用是因为 `ArrayList[String]` 定下了 `E`；`stream.map(fn(...) => ...)` 得写成 `stream.map[String](...)`，因为 `R` 是方法自己的类型变量。
- 类型里带 `throws Error` 的函数值不能传给 Java，因为 Java 看不到这个子句（`SPR-TYPE-CALLABLE-THROWS`）。把错误在具名函数里处理掉，再传一个调用它的 lambda。

变长参数（`String...`）接收末尾的零个或多个实参，编译器把它们打包成数组；如果传的正好是那个类的 Java 数组（不透明值），就原样传过去。固定参数个数的重载优先。`Path.of("etc", "sprig")`、`Files.exists(path)`、`String.format(...)`、`String.join(...)` 都是这样用的。只有元素是类型变量的变长参数（`T...`，比如 `Arrays.asList`）仍然不支持。

## 先查，再写

写调用代码之前，先用 `sprig api` 看看 Sprig 是怎么理解这个类的。它会列出每个成员在 Sprig 里的签名，能不能用，不能用的原因是什么。加 `--json` 时，这些信息在 `interopLevel`、`interopReasonCodes` 和 `adaptation` 等字段里：

```bash
sprig api java.time.LocalDate --member parse
sprig api com.example.Client --classpath lib/client.jar --json
```

如果要频繁使用某个 Java 类，可以用 `sprig wrap` 生成一个 Sprig 包装文件。生成的是普通源码，你可以随意修改：

```bash
sprig wrap com.example.Client --out src/client.spr --classpath lib/client.jar
```

`wrap` 写文件之前，会先用同一个 classpath 检查生成的代码。已有的文件默认不会被覆盖，要覆盖得加 `--force`。完整规则见[包装生成器（英文）](/en/reference/jvm/wrap)。

## 还不支持的

- **数组语法**：没有数组字面量、数组类型标注、下标和遍历，数组只能原样传递。
- **通配符语法**：带通配符类型的值可以持有和传递，但不能在 Sprig 的声明里写通配符，也不能穿过 `? extends` 往里加元素。像 Brigadier 这样层层嵌套的 builder API 可能仍然需要一个简单的 Java 适配层，见 [Fabric 模组](/guide/fabric)。
- **元素是类型变量的变长参数**（`T...`）：没有可以打包的元素类。
- **泛型推断**：类型参数要自己写，也没有协变和逆变。
- **没有注解的库**：什么都不标的库还是按 Sprig 的保守规则，引用结果一律 `T?`；只有注解（运行时可见的或从 class 文件读到的）和 `@NullMarked` 这类默认声明才会改变它。
- **Java 内部的计算**：Java 方法里发生的 `int` 溢出，不会触发 Sprig 的数值错误。

完整列表见[已知限制（英文）](/en/reference/language/known-limitations)。
