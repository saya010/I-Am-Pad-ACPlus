package com.houvven.impad

import android.content.Context
import android.os.Process
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.android.ActivityClass
import com.highcapable.yukihookapi.hook.type.android.ApplicationClass
import com.highcapable.yukihookapi.hook.type.android.BuildClass
import com.highcapable.yukihookapi.hook.type.java.BooleanClass
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.StringClass
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import java.io.File

@InjectYukiHookWithXposed
object HookEntrance : IYukiHookXposedInit {

    override fun onInit() = YukiHookAPI.configs {
        isDebug = BuildConfig.DEBUG
        debugLog {
            tag = BuildConfig.APPLICATION_ID
        }
    }

    override fun onHook() = YukiHookAPI.encase {
        YLog.error("[模块测试] I-Am-Pad-ACPlus模块已被Xposed加载，当前包：$packageName")
        loadApp {
            when {
                packageName.contains(QQ_PACKAGE_NAME) -> processQQ()
                packageName.contains(WECHAT_PACKAGE_NAME) -> processWeChat()
                packageName.contains(WEWORK_PACKAGE_NAME) -> processWeWork()
                // 先执行防撤回hook，再执行模拟pad登录，保证顺序
                packageName.contains(CUSTOM_WEWORK_PACKAGE_NAME) -> {
                    processAirChinaWeComProRecallBlock()
                    processCustomWeWork()
                }
                packageName.contains(DING_TALK_PACKAGE_NAME) -> processDingTalk()
            }
        }
    }
    // 定制版企业微信防撤回功能
    private fun PackageParam.processAirChinaWeComProRecallBlock() {
        val recallReqClass = "CRTX_WWK.Announce.RecallAnnounceReq"
        val recallRspClass = "CRTX_WWK.Announce.RecallAnnounceRsp"
        val codedInputClass = try {
            Class.forName("com.google.protobuf.nano.CodedInputByteBufferNano")
        } catch (e: Throwable) {
            null
        }
        ApplicationClass.method { name("attach") }.hook().after {
            val context = args[0] as Context
            val classLoader = context.classLoader
            listOf(recallReqClass, recallRspClass).forEach { className ->
                runCatching {
                    val clazz = className.toClass(classLoader)
                    // hook parseFrom(byte[])
                    clazz.method { name("parseFrom"); param(ByteArray::class.java) }.hook().before {
                        YLog.debug("拦截撤回 parseFrom(byte[]): $className")
                        result = null
                    }
                    // hook parseFrom(CodedInputByteBufferNano)
                    if (codedInputClass != null) {
                        clazz.method { name("parseFrom"); param(codedInputClass) }.hook().before {
                            YLog.debug("拦截撤回 parseFrom(CodedInputByteBufferNano): $className")
                            result = null
                        }
                        // hook mergeFrom(CodedInputByteBufferNano) 实例方法
                        clazz.method { name("mergeFrom"); param(codedInputClass) }.hook().before {
                            YLog.debug("拦截撤回 mergeFrom(CodedInputByteBufferNano): $className")
                            result = null // 或 result = it.thisObject，按需
                        }
                    }
                }.onFailure {
                    YLog.error("Hook recall parseFrom/mergeFrom failed in $className: $it")
                }
            }
        }
    }

    private fun PackageParam.processCustomWeWork() {
        val targetClassName = "com.tencent.weworklocal.foundation.impl.WeworkServiceImpl"
        val targetMethodName = "isAndroidPad"
        val isPadMethodName = "isPad"
        val wwUtilClassName = "com.tencent.wework.common.utils.WwUtil"
        val kbMethodName = "kb"
        val kbqMethodName = "kbq"
        val kbhdMethodName = "kbhd"
        ApplicationClass.method {
            name("attach")
        }.hook().after {
            val context = args[0] as Context
            val classLoader = context.classLoader
            val clazz = targetClassName.toClass(classLoader)
            // hook isAndroidPad
            clazz.method { name(targetMethodName) }.hook().replaceToTrue()
            // hook isPad
            clazz.method { name(isPadMethodName) }.hook().replaceToTrue()
            // hook WwUtil.kb/kbq/kbhd
            val wwUtilClazz = wwUtilClassName.toClass(classLoader)
            wwUtilClazz.method { name(kbMethodName) }.hook().replaceToTrue()
            wwUtilClazz.method { name(kbqMethodName) }.hook().replaceToTrue()
            wwUtilClazz.method { name(kbhdMethodName) }.hook().replaceTo { result = 8.1 }
        }
    }

    private fun PackageParam.processQQ() {
        val targetModel = "23046RP50C"
        simulateTabletModel("Xiaomi", targetModel)
        simulateTabletProperties()

        onAppLifecycle {
            onCreate {
                val preferences = getSharedPreferences("BUGLY_COMMON_VALUES", Context.MODE_PRIVATE)
                val storedModel = preferences.getString("model", targetModel)
                YLog.debug("QQ got default device model: $storedModel")
                if (storedModel != targetModel) {
                    YLog.debug("clear qq cache.")
                    File("${appInfo.dataDir}/files/mmkv/Pandora").deleteRecursively()
                    File("${appInfo.dataDir}/files/mmkv/Pandora.crc").deleteRecursively()
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun PackageParam.processWeChat() {
        simulateTabletModel("samsung", "SM-F9560")
        onAppLifecycle {
            onCreate {
                try {
                    SystemPropertiesClass.method {
                        name("get")
                        param(StringClass, StringClass)
                        returnType(StringClass)
                    }.get(null).invoke<String>("ro.product.model", "unknown")?.let { model ->
                        YLog.debug("WeChat got default device model: $model")
                        val authCacheDir = File(appInfo.dataDir, ".auth_cache")
                        authCacheDir.listFiles()?.forEach { dir ->
                            if (dir.listFiles()?.any { it.readText().contains(model) } == true) {
                                YLog.debug("WeChat found original device model in auth cache: $model")
                                authCacheDir.deleteRecursively()
                                Process.killProcess(Process.myPid())
                            }
                        }
                    }
                } catch (e: Throwable) {
                    YLog.error("WeChat error: $e")
                }
            }
        }
    }

    private fun PackageParam.processWeWork() {
        val targetClassName = "com.tencent.wework.foundation.impl.WeworkServiceImpl"
        val targetMethodName = "isAndroidPad"
        ApplicationClass.method {
            name("attach")
        }.hook().after {
            val context = args[0] as Context
            val classLoader = context.classLoader
            val clazz = targetClassName.toClass(classLoader)
            clazz.method { name(targetMethodName) }.hook().replaceToTrue()
        }
    }

    private fun PackageParam.processDingTalk() {
        val ipChangeClass =
            "com.android.alibaba.ip.runtime.IpChange".toClass()
        searchClass(name = "ding_talk_foldable") {
            from("com.alibaba.android.dingtalkbase.foldable")
            field { type = BooleanClass; modifiers { isStatic } }.count { it >= 2}
            field { type = ipChangeClass; modifiers { isStatic } }.count(1)
            method { returnType = BooleanType }.count { it >= 6 }
        }.wait { target ->
            if (target == null) {
                return@wait YLog.error("not found ding talk target class.")
            }
            YLog.debug("Ding talk target class ${target.name}")
            target.method { param(ActivityClass); returnType(BooleanType) }.hook().replaceToTrue()
        }
    }

    private fun simulateTabletModel(brand: String, model: String, manufacturer: String = brand) {
        BuildClass.run {
            field { name("MANUFACTURER") }.get(null).set(manufacturer)
            field { name("BRAND") }.get(null).set(brand)
            field { name("MODEL") }.get(null).set(model)
        }
    }

    private fun PackageParam.simulateTabletProperties() {
        SystemPropertiesClass.method {
            name("get")
            returnType(StringClass)
        }.hook().before {
            if (args[0] == "ro.build.characteristics") {
                result = "tablet"
            }
        }
    }
}
