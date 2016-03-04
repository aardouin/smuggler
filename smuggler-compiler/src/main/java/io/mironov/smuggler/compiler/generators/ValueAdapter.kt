package io.mironov.smuggler.compiler.generators

import io.mironov.smuggler.compiler.ClassRegistry
import io.mironov.smuggler.compiler.InvalidAutoParcelableException
import io.mironov.smuggler.compiler.common.GeneratorAdapter
import io.mironov.smuggler.compiler.common.Methods
import io.mironov.smuggler.compiler.common.Types
import io.mironov.smuggler.compiler.model.AutoParcelableClassSpec
import io.mironov.smuggler.compiler.model.AutoParcelablePropertySpec
import io.mironov.smuggler.compiler.signature.GenericType
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.util.HashMap

internal interface ValueAdapter {
  fun read(adapter: GeneratorAdapter, context: ValueContext)
  fun write(adapter: GeneratorAdapter, context: ValueContext)
}

internal class ValueAdapterFactory private constructor(
    private val registry: ClassRegistry
) {
  companion object {
    fun from(registry: ClassRegistry): ValueAdapterFactory {
      return ValueAdapterFactory(registry)
    }
  }

  private val ADAPTERS = HashMap<Type, ValueAdapter>().apply {
    put(Types.BOOLEAN, BooleanValueAdapter)
    put(Types.BYTE, ByteValueAdapter)
    put(Types.CHAR, CharValueAdapter)
    put(Types.DOUBLE, DoubleValueAdapter)
    put(Types.FLOAT, FloatValueAdapter)
    put(Types.INT, IntValueAdapter)
    put(Types.LONG, LongValueAdapter)
    put(Types.SHORT, ShortValueAdapter)

    put(Types.BOXED_BOOLEAN, BoxedBooleanValueAdapter)
    put(Types.BOXED_BYTE, BoxedByteValueAdapter)
    put(Types.BOXED_CHAR, BoxedCharValueAdapter)
    put(Types.BOXED_DOUBLE, BoxedDoubleValueAdapter)
    put(Types.BOXED_FLOAT, BoxedFloatValueAdapter)
    put(Types.BOXED_INT, BoxedIntValueAdapter)
    put(Types.BOXED_LONG, BoxedLongValueAdapter)
    put(Types.BOXED_SHORT, BoxedShortValueAdapter)

    put(Types.STRING, StringValueAdapter)
    put(Types.ANDROID_BUNDLE, BundleValueAdapter)
  }

  fun create(spec: AutoParcelableClassSpec, property: AutoParcelablePropertySpec): ValueAdapter {
    return create(spec, property, property.type)
  }
  
  fun create(spec: AutoParcelableClassSpec, property: AutoParcelablePropertySpec, generic: GenericType): ValueAdapter {
    return ADAPTERS[generic.asAsmType()] ?: run {
      val type = generic.asAsmType()

      if (registry.isSubclassOf(type, Types.ENUM)) {
        return EnumValueAdapter
      }

      if (type == Types.MAP) {
        return createMap(spec, property, generic)
      }

      if (type == Types.LIST) {
        return createCollection(Types.LIST, Types.ARRAY_LIST, spec, property, generic)
      }

      if (type == Types.SET) {
        return createCollection(Types.SET, Types.LINKED_SET, spec, property, generic)
      }

      if (registry.isSubclassOf(type, Types.ANDROID_SPARSE_ARRAY)) {
        return createSparseArray(spec, property)
      }

      if (registry.isSubclassOf(type, Types.ANDROID_SPARSE_BOOLEAN_ARRAY)) {
        return SparseBooleanArrayValueAdapter
      }

      if (registry.isSubclassOf(type, Types.ANDROID_PARCELABLE)) {
        return ParcelableValueAdapter
      }

      if (generic is GenericType.ArrayType) {
        return ArrayPropertyAdapter(create(spec, property, generic.elementType))
      }

      if (type.sort == Type.ARRAY) {
        return ArrayPropertyAdapter(create(spec, property, GenericType.RawType(Types.getElementType(type))))
      }

      if (registry.isSubclassOf(type, Types.SERIALIZABLE)) {
        return SerializableValueAdapter
      }

      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' has unsupported type ''{1}''", property.name, type.className)
    }
  }

  private fun createCollection(collection: Type, implementation: Type, spec: AutoParcelableClassSpec, property: AutoParcelablePropertySpec, generic: GenericType): ValueAdapter {
    val adapters = createAdaptersForParameterizedType(spec, property, generic)

    if (adapters.size != 1) {
      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must have exactly one type argument", property.name)
    }

    return CollectionValueAdapter(collection, implementation, adapters[0])
  }

  private fun createMap(spec: AutoParcelableClassSpec, property: AutoParcelablePropertySpec, generic: GenericType): ValueAdapter {
    val adapters = createAdaptersForParameterizedType(spec, property, generic)

    if (adapters.size != 2) {
      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must have exactly two type arguments", property.name)
    }

    return MapValueAdapter(adapters[0], adapters[1])
  }

  private fun createSparseArray(spec: AutoParcelableClassSpec, property: AutoParcelablePropertySpec): ValueAdapter {
    if (property.type !is GenericType.ParameterizedType) {
      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must be parameterized as ''SparseArray<Foo>''", property.name)
    }

    if (property.type.typeArguments.size != 1) {
      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must have exactly one type argument", property.name)
    }

    if (property.type.typeArguments[0] !is GenericType.RawType) {
      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must be parameterized with a raw type", property.name)
    }

    return SparseArrayValueAdapter(property.type.typeArguments[0].asRawType().type)
  }

  private fun createAdaptersForParameterizedType(spec: AutoParcelableClassSpec, property: AutoParcelablePropertySpec, generic: GenericType): List<ValueAdapter> {
    if (generic !is GenericType.ParameterizedType) {
      throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must be parameterized", property.name)
    }

    generic.typeArguments.forEach {
      if (it !is GenericType.RawType && it !is GenericType.ParameterizedType && it !is GenericType.ArrayType) {
        throw InvalidAutoParcelableException(spec.clazz.type, "Property ''{0}'' must be parameterized with raw or generic type", property.name)
      }
    }

    return generic.typeArguments.map {
      create(spec, property, it)
    }
  }
}

internal abstract class OptionalValueAdapter() : ValueAdapter {
  final override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    val start = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.ifZCmp(Opcodes.IFEQ, start)

    adapter.readNotNullValue(context)
    adapter.goTo(end)

    adapter.mark(start)
    adapter.pushNull()

    adapter.mark(end)
  }

  final override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    val start = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.value())
    adapter.ifNull(start)

    adapter.loadLocal(context.parcel())
    adapter.push(1)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))
    adapter.writeNotNullValue(context)
    adapter.goTo(end)

    adapter.mark(start)
    adapter.loadLocal(context.parcel())
    adapter.push(0)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))

    adapter.mark(end)
  }

  private fun GeneratorAdapter.readNotNullValue(context: ValueContext) = readNotNull(this, context)
  private fun GeneratorAdapter.writeNotNullValue(context: ValueContext) = writeNotNull(this, context)

  abstract fun readNotNull(adapter: GeneratorAdapter, context: ValueContext)
  abstract fun writeNotNull(adapter: GeneratorAdapter, context: ValueContext)
}

internal open class SimpleValueAdapter(
    private val type: Type,
    private val reader: String,
    private val writer: String
) : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get(reader, type))
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get(writer, Types.VOID, type))
  }
}

internal open class SimpleBoxedValueAdapter(
    private val delegate: ValueAdapter,
    private val unboxed: Type,
    private val boxed: Type,
    private val unboxer: String,
    private val boxer: String
) : OptionalValueAdapter() {
  override fun readNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    delegate.read(adapter, context)
    adapter.invokeStatic(boxed, Methods.get(boxer, boxed, unboxed))
  }

  override fun writeNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.value())
    adapter.invokeVirtual(boxed, Methods.get(unboxer, unboxed))

    delegate.write(adapter, context.typed(GenericType.RawType(unboxed)).apply {
      value(adapter.newLocal(unboxed).apply {
        adapter.storeLocal(this)
      })
    })
  }
}

internal object ByteValueAdapter : SimpleValueAdapter(Types.BYTE, "readByte", "writeByte")
internal object DoubleValueAdapter : SimpleValueAdapter(Types.DOUBLE, "readDouble", "writeDouble")
internal object FloatValueAdapter : SimpleValueAdapter(Types.FLOAT, "readFloat", "writeFloat")
internal object IntValueAdapter : SimpleValueAdapter(Types.INT, "readInt", "writeInt")
internal object LongValueAdapter : SimpleValueAdapter(Types.LONG, "readLong", "writeLong")
internal object StringValueAdapter : SimpleValueAdapter(Types.STRING, "readString", "writeString")
internal object BundleValueAdapter : SimpleValueAdapter(Types.ANDROID_BUNDLE, "readBundle", "writeBundle")
internal object SparseBooleanArrayValueAdapter : SimpleValueAdapter(Types.ANDROID_SPARSE_BOOLEAN_ARRAY, "readSparseBooleanArray", "writeSparseBooleanArray")

internal object BoxedByteValueAdapter : SimpleBoxedValueAdapter(ByteValueAdapter, Types.BYTE, Types.BOXED_BYTE, "byteValue", "valueOf")
internal object BoxedCharValueAdapter : SimpleBoxedValueAdapter(CharValueAdapter, Types.CHAR, Types.BOXED_CHAR, "charValue", "valueOf")
internal object BoxedDoubleValueAdapter : SimpleBoxedValueAdapter(DoubleValueAdapter, Types.DOUBLE, Types.BOXED_DOUBLE, "doubleValue", "valueOf")
internal object BoxedFloatValueAdapter : SimpleBoxedValueAdapter(FloatValueAdapter, Types.FLOAT, Types.BOXED_FLOAT, "floatValue", "valueOf")
internal object BoxedIntValueAdapter : SimpleBoxedValueAdapter(IntValueAdapter, Types.INT, Types.BOXED_INT, "intValue", "valueOf")
internal object BoxedLongValueAdapter : SimpleBoxedValueAdapter(LongValueAdapter, Types.LONG, Types.BOXED_LONG, "longValue", "valueOf")
internal object BoxedShortValueAdapter : SimpleBoxedValueAdapter(ShortValueAdapter, Types.SHORT, Types.BOXED_SHORT, "shortValue", "valueOf")
internal object BoxedBooleanValueAdapter : SimpleBoxedValueAdapter(BooleanValueAdapter, Types.BOOLEAN, Types.BOXED_BOOLEAN, "booleanValue", "valueOf")

internal object CharValueAdapter : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.cast(Types.INT, Types.CHAR)
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.cast(Types.CHAR, Types.INT)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))
  }
}

internal object ShortValueAdapter : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.cast(Types.INT, Types.SHORT)
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.cast(Types.SHORT, Types.INT)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))
  }
}

internal object BooleanValueAdapter : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    val start = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.ifZCmp(Opcodes.IFEQ, start)

    adapter.push(true)
    adapter.goTo(end)
    adapter.mark(start)
    adapter.push(false)

    adapter.mark(end)
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    val start = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.ifZCmp(Opcodes.IFEQ, start)
    adapter.push(1)
    adapter.goTo(end)
    adapter.mark(start)
    adapter.push(0)
    adapter.mark(end)

    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))
  }
}

internal object EnumValueAdapter : OptionalValueAdapter() {
  override fun readNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.invokeStatic(context.type.asAsmType(), Methods.get("values", Types.getArrayType(context.type.asAsmType())))
    adapter.swap(Types.INT, Types.getArrayType(context.type.asAsmType()))
    adapter.arrayLoad(context.type.asAsmType())
  }

  override fun writeNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.invokeVirtual(context.type.asAsmType(), Methods.get("ordinal", Types.INT))
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))
  }
}

internal object SerializableValueAdapter : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readSerializable", Types.SERIALIZABLE))
    adapter.checkCast(context.type.asAsmType())
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.checkCast(Types.SERIALIZABLE)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeSerializable", Types.VOID, Types.SERIALIZABLE))
  }
}

internal object ParcelableValueAdapter : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.push(context.type.asAsmType())
    adapter.invokeVirtual(Types.CLASS, Methods.get("getClassLoader", Types.CLASS_LOADER))
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readParcelable", Types.ANDROID_PARCELABLE, Types.CLASS_LOADER))
    adapter.checkCast(context.type.asAsmType())
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.checkCast(Types.ANDROID_PARCELABLE)
    adapter.loadLocal(context.flags())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeParcelable", Types.VOID, Types.ANDROID_PARCELABLE, Types.INT))
  }
}

internal class ArrayPropertyAdapter(
    private val delegate: ValueAdapter
) : OptionalValueAdapter() {
  final override fun readNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    val index = adapter.newLocal(Types.INT)
    val length = adapter.newLocal(Types.INT)

    val elementType = Types.getElementType(context.type.asAsmType())
    val elements = adapter.newLocal(context.type.asAsmType())

    val begin = adapter.newLabel()
    val body = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.storeLocal(length)

    adapter.loadLocal(length)
    adapter.newArray(elementType)
    adapter.storeLocal(elements)

    adapter.push(0)
    adapter.storeLocal(index)

    adapter.mark(begin)
    adapter.loadLocal(index)
    adapter.loadLocal(length)

    adapter.ifICmp(Opcodes.IFLT, body)
    adapter.goTo(end)

    adapter.mark(body)
    adapter.loadLocal(elements)
    adapter.loadLocal(index)
    adapter.readElement(context.asElementContext())
    adapter.arrayStore(elementType)

    adapter.iinc(index, 1)
    adapter.goTo(begin)
    adapter.mark(end)

    adapter.loadLocal(elements)
  }

  final override fun writeNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    val index = adapter.newLocal(Types.INT)
    val length = adapter.newLocal(Types.INT)

    val elementType = Types.getElementType(context.type.asAsmType())
    val element = adapter.newLocal(elementType)

    val begin = adapter.newLabel()
    val body = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.push(0)
    adapter.storeLocal(index)

    adapter.loadLocal(context.value())
    adapter.arrayLength()
    adapter.storeLocal(length)

    adapter.loadLocal(context.parcel())
    adapter.loadLocal(length)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))

    adapter.mark(begin)
    adapter.loadLocal(index)
    adapter.loadLocal(length)

    adapter.ifICmp(Opcodes.IFLT, body)
    adapter.goTo(end)

    adapter.mark(body)
    adapter.loadLocal(context.value())
    adapter.loadLocal(index)
    adapter.arrayLoad(elementType)
    adapter.storeLocal(element)
    adapter.writeElement(context.asElementContext().apply {
      value(element)
    })

    adapter.iinc(index, 1)
    adapter.goTo(begin)
    adapter.mark(end)
  }

  private fun ValueContext.asElementContext(): ValueContext {
    return if (type !is GenericType.ArrayType) {
      typed(GenericType.RawType(Types.getElementType(type.asAsmType())))
    } else {
      typed(type.elementType)
    }
  }

  private fun GeneratorAdapter.readElement(context: ValueContext) = delegate.read(this, context)
  private fun GeneratorAdapter.writeElement(context: ValueContext) = delegate.write(this, context)
}

internal class SparseArrayValueAdapter(
    private val element: Type
) : ValueAdapter {
  override fun read(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.push(element)
    adapter.invokeVirtual(Types.CLASS, Methods.get("getClassLoader", Types.CLASS_LOADER))
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readSparseArray", Types.ANDROID_SPARSE_ARRAY, Types.CLASS_LOADER))
    adapter.checkCast(context.type.asAsmType())
  }

  override fun write(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.checkCast(Types.ANDROID_SPARSE_ARRAY)
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeSparseArray", Types.VOID, Types.ANDROID_SPARSE_ARRAY))
  }
}

internal class CollectionValueAdapter(
    private val collection: Type,
    private val implementation: Type,
    private val delegate: ValueAdapter
) : OptionalValueAdapter() {
  override fun readNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    val parameterizedType = context.type.asParameterizedType()
    val elementType = parameterizedType.typeArguments[0].asAsmType()

    val index = adapter.newLocal(Types.INT)
    val length = adapter.newLocal(Types.INT)
    val elements = adapter.newLocal(collection)

    val begin = adapter.newLabel()
    val body = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.parcel())
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("readInt", Types.INT))
    adapter.storeLocal(length)

    adapter.newInstance(implementation, Methods.getConstructor())
    adapter.storeLocal(elements)

    adapter.push(0)
    adapter.storeLocal(index)

    adapter.mark(begin)
    adapter.loadLocal(index)
    adapter.loadLocal(length)

    adapter.ifICmp(Opcodes.IFLT, body)
    adapter.goTo(end)

    adapter.mark(body)
    adapter.loadLocal(elements)
    adapter.readElement(context.typed(parameterizedType.typeArguments[0]))
    adapter.checkCast(elementType)
    adapter.invokeInterface(Types.COLLECTION, Methods.get("add", Types.BOOLEAN, Types.OBJECT))
    adapter.pop()

    adapter.iinc(index, 1)
    adapter.goTo(begin)
    adapter.mark(end)

    adapter.loadLocal(elements)
  }

  override fun writeNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    val parameterizedType = context.type.asParameterizedType()
    val elementType = parameterizedType.typeArguments[0].asAsmType()

    val element = adapter.newLocal(elementType)
    val iterator = adapter.newLocal(Types.ITERATOR)

    val begin = adapter.newLabel()
    val end = adapter.newLabel()

    adapter.loadLocal(context.parcel())
    adapter.loadLocal(context.value())
    adapter.invokeInterface(Types.COLLECTION, Methods.get("size", Types.INT))
    adapter.invokeVirtual(Types.ANDROID_PARCEL, Methods.get("writeInt", Types.VOID, Types.INT))

    adapter.loadLocal(context.value())
    adapter.invokeInterface(Types.COLLECTION, Methods.get("iterator", Types.ITERATOR))
    adapter.storeLocal(iterator)

    adapter.mark(begin)
    adapter.loadLocal(iterator)
    adapter.invokeInterface(Types.ITERATOR, Methods.get("hasNext", Types.BOOLEAN))
    adapter.ifZCmp(Opcodes.IFEQ, end)

    adapter.loadLocal(iterator)
    adapter.invokeInterface(Types.ITERATOR, Methods.get("next", Types.OBJECT))
    adapter.checkCast(elementType)
    adapter.storeLocal(element)
    adapter.writeElement(context.typed(parameterizedType.typeArguments[0]).apply {
      value(element)
    })

    adapter.goTo(begin)
    adapter.mark(end)
  }

  private fun GeneratorAdapter.readElement(context: ValueContext) = delegate.read(this, context)
  private fun GeneratorAdapter.writeElement(context: ValueContext) = delegate.write(this, context)
}

internal class MapValueAdapter(
    private val key: ValueAdapter,
    private val value: ValueAdapter
) : OptionalValueAdapter() {
  override fun readNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    adapter.pushNull()
  }

  override fun writeNotNull(adapter: GeneratorAdapter, context: ValueContext) {
    // empty for now
  }
}
