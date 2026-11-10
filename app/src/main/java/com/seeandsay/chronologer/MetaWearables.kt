package com.seeandsay.chronologer

import android.app.Activity
import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Calls the Meta Wearables DAT SDK without a hard class reference.
 *
 * mwdat-* is compileOnly and is omitted entirely when Maven Central cannot
 * serve it, so phone and emulator builds must not import com.meta.wearable types.
 * A missing or unpackaged SDK returns [MetaWearablesResult.Unavailable].
 */
object MetaWearables {
    private const val WEARABLES_CLASS = "com.meta.wearable.dat.core.Wearables"

    fun isPresent(): Boolean = wearablesClass() != null

    fun initialize(context: Context): MetaWearablesResult {
        return invoke(
            methodName = "initialize",
            parameterType = Context::class.java,
            argument = context.applicationContext,
        )
    }

    fun startRegistration(activity: Activity): MetaWearablesResult {
        return invoke(
            methodName = "startRegistration",
            parameterType = Activity::class.java,
            argument = activity,
        )
    }

    fun startUnregistration(activity: Activity): MetaWearablesResult {
        return invoke(
            methodName = "startUnregistration",
            parameterType = Activity::class.java,
            argument = activity,
        )
    }

    private fun invoke(
        methodName: String,
        parameterType: Class<*>,
        argument: Any,
    ): MetaWearablesResult {
        val type = wearablesClass() ?: return MetaWearablesResult.Unavailable
        val method = type.methods.firstOrNull { candidate ->
            candidate.name == methodName &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == parameterType
        } ?: return MetaWearablesResult.Failed("$methodName($parameterType) is not on $WEARABLES_CLASS")

        val receiver = if (Modifier.isStatic(method.modifiers)) {
            null
        } else {
            try {
                type.getField("INSTANCE").get(null)
                    ?: return MetaWearablesResult.Failed("$WEARABLES_CLASS has no INSTANCE")
            } catch (error: ReflectiveOperationException) {
                return MetaWearablesResult.Failed(error.message ?: "$WEARABLES_CLASS has no INSTANCE")
            }
        }

        return try {
            method.invoke(receiver, argument)
            MetaWearablesResult.Success
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            MetaWearablesResult.Failed(cause.message ?: "$methodName failed")
        } catch (error: ReflectiveOperationException) {
            MetaWearablesResult.Failed(error.message ?: "$methodName could not be called")
        }
    }

    private fun wearablesClass(): Class<*>? {
        return try {
            Class.forName(WEARABLES_CLASS)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}

sealed interface MetaWearablesResult {
    data object Unavailable : MetaWearablesResult
    data object Success : MetaWearablesResult
    data class Failed(val reason: String) : MetaWearablesResult
}
