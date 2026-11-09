# Shizuku 通过反射按类名实例化 UserService，AIDL 的 Stub 也不能被裁剪/混淆。
-keep class dev.cuteaplane.timerrunner.UserService { *; }
-keep class dev.cuteaplane.timerrunner.IUserService { *; }
-keep class dev.cuteaplane.timerrunner.IUserService$Stub { *; }
