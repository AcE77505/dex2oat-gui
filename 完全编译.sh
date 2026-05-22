#!/system/bin/sh

# 酷安@风也吹不动
# 使用MT管理器点执行，若执意使用其他终端 请使用能接收input输入的终端
# 建议别随意改动命令前后逻辑，可能会导致选项间冲突，谨慎修改（能正常运行就行了）

# Latest: 2023/11/26 适配安卓14

# 2023/05/28  修复部分bug

PATH="/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin:$PATH"
export PATH
[ "$(whoami)" != "root" ] && echo '要root' && exit 0
# umask 022
env_tmp=`env | grep 'SHELL=' | egrep 'zsh|csh'  2>/dev/null`
if [ "$env_tmp" != "" ]; then
    echo '--执行脚本采用的解释器不适配'
    echo '--可以使用含bash解释器来执行(可能会部分异常，例如转义换行异常)'
    echo '--避免出问题，建议使用MT管理器点击“完全编译.sh”再执行'
    echo '--若处于必须使用当前终端，请您在当前终端输入↓'
    echo '命令行：sh 环境.sh'
    exit 0
fi
if [ "$(env | grep 'SHELL=' | grep -i 'bash')" != "" ]; then
    echo ''
    echo '--您当前终端为：以bash解释器来执行(可能会部分异常，例如换行异常)'
    echo '--避免出问题，建议使用MT管理器点击“完全编译.sh”再执行'
    echo '--若处于必须使用当前终端，请您在当前终端输入↓'
    echo '命令行：sh 环境.sh'
    echo '但现在仍为您以bash解释器来继续执行脚本'
fi
cd ${0%/*}

### 可以改动 ###
## 固定包名，不用每次输入
package=""
## 编译类型，可选speed everything
compile_filter="speed"
## 完全编译最后增加的参数(一般用于调试，如方法内联)，首位无需留空格
# last_add_options="--inline-max-code-units=64 --no-inline-from=core-oj.jar"


### 若完全体编译报错再去掉注释尝试一遍 ###
## CPU核心数（若超过实际核心数量 完全体编译将报错）
# cpu_num=8
## CPU亲和度，避免出问题，个数务必和上面一致，从0开始
# aff='0,1,2,3,4,5,6,7'



echo ''
echo ' --如使用MT管理器 最右下角选项唤起输入法--'
echo ' --除包名外只能输入Y/y，任意键/仅回车均为否--'
echo ''
echo " -待编译类型：$compile_filter"

target_log='./last_compile.log'
# System and Third party (ALL package)
sys_package=`pm list package -s | awk -F 'package:' '{print $2}'`
user_package=`pm list package -3 | awk -F 'package:' '{print $2}'`
user_var=""
system_var=""
api=`getprop ro.build.version.sdk`
aapt_check="false"
if [[ `ls | grep aapt` != "" ]] ; then
    aapt_path=/data/mytempdir
    aapt_tool="$aapt_path/aapt"
    if [ ! -f "$aapt_tool" ]; then
        mkdir -m 0755 $aapt_path
        cp aapt $aapt_tool
        chmod 0755 $aapt_tool
    fi  2>/dev/null
    [[ `$aapt_tool v  2>/dev/null | grep 'build by'` != "" ]] && aapt_check="true"
fi



################################
##########     分割线     ###########
################################

# 避免出意外，套娃多次验证包名
function maby_package()
{
    # Maby AAB package
    too_many_package() {
        local get_one_package
        local get_one_package_count
        get_one_package=`echo "$tmp" | grep '/data/app' | grep '/base.apk'`
        get_one_package_count=`printf "$get_one_package" | awk 'END{print NR}'`
        if [ $get_one_package_count -eq 1 ]; then
            is_one_package="$get_one_package"
            one_package_count="$get_one_package_count"
            # AAB package
            is_too_many_package='true'
            return 0
        else
          # Too many 'base.apk' or system AAB package(such as: WebView Gms...). The script doesn’t support your requirements, So $get_one_package_count not equal 1, will be return 1
            return 1
        fi
    }

    ###### IF ######
    # return 0: This system package
    # return 1: This third party package
    # return 2: Too many package, but not support
    # return 3: Package not found
    local tmp
    local temp
    local maby_user_package="$2"
    tmp=`pm path $1`
    if [ "$tmp" != "" ]; then
        temp=`printf "$tmp" | awk 'END{print NR}'`
        if [[ $temp -gt 1 ]] ; then
            too_many_package
            [ $? -eq 0 ] && return 1
            return 2
        fi
        temp=`echo "$tmp" | grep '/data/app/'`
        if [[ "$temp" != "" ]] ; then
            [[ "$maby_user_package" == "is_sys_package" ]] && is_sys_update_package='true'
            return 1
        fi
        return 0
    else
        return 3
    fi
}


function package_check()
{
    maby_package_result() {
        case $1 in
            0)  is_sys_package='true'
                return 0 ;;
            1)  is_user_package='true'
                return 0 ;;
            2)  return 1 ;;
            3)  return 2 ;;
            *)  return 3 ;;
        esac
    }
    local tmp="$1" user_tmp sys_tmp
    user_tmp=`echo "$user_package" | grep "$tmp"`
    sys_tmp=`echo "$sys_package" | grep "$tmp"`
    if [[ "$user_tmp" != "" || "$sys_tmp" != "" ]] ; then
        if [ "$user_tmp" != "" ]; then
            maby_package "$tmp"
            maby_package_result $?
            return $?
        elif [ "$sys_tmp" != "" ]; then
          # 再次检查是否为系统软件(判断系统软件是否被更新成用户软件)
          #Check the system package again, If system package :return 0, third party package: return 1
            maby_package "$tmp" "is_sys_package"
            maby_package_result $?
            return $?
        else
          # I don't know what happened, run err(function), process will be exit
            return 3
        fi
    else
      # Not found
        return 2
    fi
}


function get_package_name()
{
    local is_aab="$1"
    if [[ "$aapt_check" == "true" ]] ; then
        if [[ "$is_aab" == "true" ]] ; then
            package_name=`$aapt_tool dump badging ${is_one_package/'package:'/} | grep 'application-label-zh' | head -n1 | awk -F ':' '{print $2}'`
        else
            package_name=`pm path $package`
            package_name=`$aapt_tool dump badging ${package_name/'package:'/} | grep 'application-label-zh' | head -n1 | awk -F ':' '{print $2}'`
        fi
        [[ "$package_name" != "" ]] && echo " -- 软件名 $package_name --"
    fi  2>/dev/null
    return 0
}


function input_package()
{
    local tmp=""
    input_success() {
        case $1 in
            0)
                if [ "$is_too_many_package" == "true" ]; then
                    echo " --$2包名为AAB软件--"
                    get_package_name "true"
                elif [ "$is_sys_package" == "true" ]; then
                    echo " --$2包名为系统软件--"
                    get_package_name "false"
                elif [ "$is_sys_update_package" == "true" ]; then
                    echo " --$2包名为系统软件(已更新)--"
                    get_package_name "false"
                else
                    echo " --$2包名为第三方软件--"
                    get_package_name "false"
                fi
                return 0
                ;;
            1)
                if [ "$2" == "" ]; then
                    echo "该AAB软件暂不支持，请换一个(剩$i次)"
                else
                    echo "Error：该AAB软件暂不支持，请换一个"
                    echo ''
                fi
                return 1
                ;;
            2)
                if [ "$2" == "" ]; then
                    echo "包名有误，检查软件是否已安装(剩$i次)"
                else
                    echo "Error：$2包名有误 可能已卸载，重新输入"
                    echo ''
                fi
                return 2
                ;;
            3)
                err '未知异常，退出'
                ;;
        esac
    }
    if [ "$package" != "" ]; then
        echo -n " -内置包名为$package 是否切换？：" ; read tmp
        case "$tmp" in
            Y | y)  ;;
            *)
                package_check "$package"
                input_success "$?" "内置"
                [ $? -eq 0 ] && return 0
                ;;
        esac
    else
        tmp=""
        package=`grep '软件包名：' last_compile.log  2>/dev/null | awk -F '软件包名：' '{print $2}' | awk -F ' ' '{print $1}'`
        tmp=`grep '软件名：' last_compile.log  2>/dev/null | awk -F '软件名：' '{print $2}' | awk -F ' ' '{print $1}'`
        if [ "$package" != "" ]; then
            echo -n " -上次记录包名为$package($tmp) 是否切换：" ; read tmp
            case "$tmp" in
                Y | y)  ;;
                *)
                    package_check "$package"
                    input_success "$?" "上次"
                    [ $? -eq 0 ] && return 0
                    ;;
            esac
        fi
    fi
    for i in `seq 4 -1 0` ; do
        echo -n " -请输入已安装软件包名(限一个)："
        read new_package
        
        if [[ "$new_package" == "" ]] ; then
            echo "包名为空(剩$i次)"
            continue
        fi
        if [ "$(pm path $new_package)" == "" ]; then
            echo "包名有误，检查软件是否已安装(剩$i次)"
            continue
        fi
        if [[ "$new_package" != "" ]] ; then
            package="$new_package"
            package_check "$package"
            input_success $?
            case $? in
                0)  return 0 ;;
                1)  continue ;;
                2)  continue ;;
            esac
        fi
    done
    exit 0
}

function options_input()
{
    local tmp
    echo ''
    echo -n 'ⓞ 一步登天？(首次+再次+完全)：'
    read xxx
    case "$xxx" in
        Y | y)
            xa='clean_profile'
            xb='profile'
            xc='compile'
            auto_profile='true'
            echo -n '  --是否自动启动app：' ; read tmp
            case "$tmp" in
                Y | y)
                    auto_start="true"
                    ;;
            esac
            echo ''
            return 0
            ;;
        'ex'|'EX'|'Ex'|'eX')
            xe=EXT_ON
            ;;
    esac
    ### 首次profile编译
    echo -n "① --是否首次$compile_filter-profile编译："
    read xxa
    case "$xxa" in
        Y | y)
            xa='clean_profile'
            ;;
        'ex'|'EX'|'Ex'|'eX')
            xe=EXT_ON
            ;;
    esac
    
    ### 对已生成热点列表进行profile编译(再次profile编译)
    echo -n "② --是否profile编译："
    read xxb
    case "$xxb" in
        Y | y)
            xb='profile'
            if [ "$xa" != "" ] ; then
                echo -n ' ----检测两项已选择，是否自动编译：' ; read xxa
                case $xxa in
                    Y | y)
                        auto_profile='true'
                        ;;
                esac
            fi
            ;;
        'ex'|'EX'|'Ex'|'eX')
            xe=EXT_ON
            ;;
    esac
    
    ### 全量+profile编译 ###
    echo -n "③ --是否全量$compile_filter编译+profile编译(完全体)："
    read xxc
    case "$xxc" in
        Y | y)
            xc='compile'
            ;;
        'ex'|'EX'|'Ex'|'eX')
            xe=EXT_ON
            ;;
        force)
            echo ' ----因force，已强制选择选项①'
            xa='clean_profile'
            xc='compile'
            force_compile="true"
            ;;
    esac
    
    ### 获取odex在内存中的信息 ###
    echo -n "④ --是否仅获取该软件在运存中的信息："
    read xxd
    case "$xxd" in
        Y | y)
            xd='only_dump'
            ;;
        'ex'|'EX'|'Ex'|'eX')
            xe=EXT_ON
            ;;
    esac
}

function check_input_options()
{
    if  echo $xa $xb $xc $xd $xe | grep -iq ^[a-z] ; then
        echo -n "\n\n--若觉得有误，是否重新输入："
        read restore_end
        case $restore_end in
            Y | y)
                echo "\n  --------Goodbye--------"
                exit 0
                ;;
            *)
                echo ''
                ;;
        esac
    else
        echo "\n什么都没选，Again"
        exit 0
    fi
}

function check_mt()
{
    if  echo $xa $xb $xc $xe | grep -iq ^[a-z] ; then
        true
    else
        return 0
    fi
    if [[ "$package" == "bin.mt.plu" || "$package" == "bin.mt.plu.caary" ]] ; then
        mt_pid=`pidof bin.mt.plu`
        mt_canary_pid=`pidof bin.mt.plu.caary`
        if [ "$(grep -i 'top-app' /proc/$mt_pid/cpuset  2>/dev/null)" != "" ]; then
            echo "\n -暂不支持在MT管理器对 “MT管理器软件”进行编译，请使用其他软件终端执行“环境.sh”来编译MT"
            exit 0
        elif [ "$(grep -i 'top-app' /proc/$mt_canary_pid/cpuset  2>/dev/null)" != "" ]; then
            echo "\n -暂不支持在MT管理器对 “MT管理器软件”进行编译，请使用其他软件终端执行“环境.sh”来编译MT"
            exit 0
        fi
    fi
}
##############################

# 第三方软件处理
function user_var_sett()
{
    user_var='true'
    # Local var
    local aa
    local aaa
    if [ "$is_too_many_package" == "true" ]; then
        [[ "$one_package_count" == "1" ]] && aa="$is_one_package"
    else
        aa=`pm path $package`
    fi
    aaa="${aa/'package:'/}"
    # Global var
    path="${aaa/'/base.apk'/}"
    dex="$path/base.apk"
    cmd_path="$path/oat"
    arm_code=`ls "$path/oat/"  2>/dev/null`
    [[ `printf "$arm_code" | awk 'END{print NR}'` -eq 2 ]] && arm_code='two'
    case "$arm_code" in
        arm)
            cpu_code=`getprop 'dalvik.vm.isa.arm.variant'`
            ;;
        arm64)
            cpu_code=`getprop 'dalvik.vm.isa.arm64.variant'`
            ;;
        two)
            # Such as 'WebView Gms Magisk...'
            cpu_code='two'
            odex="$path/oat/arm64/base.odex"
            vdex="$path/oat/arm64/base.vdex"
            art="$path/oat/arm64/base.art"
            return 0
            ;;
        "")
            err '未找到oat子目录'
            ;;
        *)
            err 'arm类型异常'
            ;;
    esac
    odex="$path/oat/$arm_code/base.odex"
    vdex="$path/oat/$arm_code/base.vdex"
    art="$path/oat/$arm_code/base.art"
}
# 系统软件处理
function system_var_sett()
{
    system_var='true'
    local aa
    aa=`pm path $package`
    dex="${aa/'package:'/}"
    # Global var
    sys_apk_name="${dex##*/}"
    path="${dex%/*}"
    odex=`find /data/dalvik-cache/ -type f -name "*\@$sys_apk_name\@classes.dex"`
    vdex=`find /data/dalvik-cache/ -type f -name "*\@$sys_apk_name\@classes.vdex"`
    if [[ "$is_only_dump" == "true" ]] ; then
        if [[ "$odex" == "" && "$vdex" == "" ]] ; then
            sys_odex_name="${sys_apk_name/'apk'/'odex'}"
            odex="$path/oat/arm64/$sys_odex_name"
            [[ ! -f "$odex" ]] && odex="$path/oat/arm/$sys_odex_name"
            [[ ! -f "$odex" ]] && err "未找到系统软件odex，您可能需要先进行“首次profile编译”"
        fi
        return 0
    else
        [[ "$odex" == "" && "$vdex" == "" ]] && err "未找到系统软件odex与vdex，您可能需要先进行“首次profile编译”"
    fi
    art="${odex/'classes.dex'/}"
    art+="classes.art"
    local tmp=0
    if [ "$(echo "$odex" | grep 'cache/arm64/')" != "" ]; then
        tmp=$(($tmp + 1))
        arm_code='arm64'
    fi
    if [ "$(echo "$odex" | grep 'cache/arm/')" != "" ]; then
        tmp=$(($tmp + 1))
        arm_code='arm'
    fi
    [ $tmp -eq 2 ] && arm_code='two'
    case "$arm_code" in
        arm)
            cpu_code=`getprop 'dalvik.vm.isa.arm.variant'`
            ;;
        arm64)
            cpu_code=`getprop 'dalvik.vm.isa.arm64.variant'`
            ;;
        two)
            # 可能有bug的地方，我没有试过(我的都更新了)，这是系统软件上含arm和arm64的，例如没有更新过的系统软件：谷歌服务、WebView等等，但按脚本代码逻辑走了一遍应该没问题
            cpu_code='two'
            tmp=`printf "$odex" | grep 'cache/arm64/'`
            if [[ `printf "$tmp" | awk 'END{print NR}'` -eq 1 ]] ; then
                odex="$tmp"
                vdex="${tmp/'classes.dex'/'classes.vdex'}"
                art="${tmp/'classes.dex'/'classes.art'}"
            else
                # 虽然上面适配了双arm的系统软件，但还是不支持AAB，这样搞没必要
                err '该软件可能是含子包/语言包的AAB系统软件，暂不适配AAB双架(arm与arm64同在)的系统软件'
            fi
            ;;
        "")
            err '未找到arm类型'
            ;;
    esac
}

function err()
{
    error="$1"
    echo "环境检查不通过：$error"
    output_log > $target_log
    exit 1
}

# 环境检查
function Env_Prop_Var()
{
    if  echo $dex $odex $vdex | grep -q .$ ; then
        true
    else
        err "apk、odex、vdex变量均为空"
    fi
    # System package
    if [ "$is_sys_package" == "true" ]; then
        [[ `printf "$odex" | awk 'END{print NR}'` -gt 1 ]] && err "该软件含多个odex文件，暂不适配"
        for i in $dex $odex $vdex ; do
            [ -f $i ] || err "系统软件或odex或vdex文件判断不准确，您可能需要先进行“首次profile编译”"
        done
        [ -d $path ] || err "系统软件路径不存在"
    fi
    # Third party package
    if [ "$is_user_package" == "true" ]; then
        for i in $dex $odex $vdex ; do
            [ -f $i ] || err "软件或odex或vdex文件未找到，您可能需要先进行“首次profile编译”"
        done
        for i in $path $cmd_path ; do
            [ -d $i ] || err "软件路径或编译路径不存在"
        done
    fi

    get_cpu_count() {
        cpu_num=0
        local no first last
        if [ ! $(echo $1 | grep -e [a-z]) ]; then
            for no in `echo "$1" | sed 's/,/\n/g'` ; do
                case "$no" in
                  [0-9])
                      cpu_num=`expr $cpu_num \+ 1`
                      ;;
                  [0-9]-[0-9])
                      last=`expr ${no:2}`
                      first=`expr ${no:0:1}`
                      if [ $(echo $last | grep -e [0-9]) ]; then
                          if [ $(echo $first | grep -e [0-9]) ]; then
                              if [ $last -gt $first ]; then
                                  cpu_num=`expr $cpu_num \+ $last - $first \+ 1`
                              fi
                          fi
                      fi
                      ;;
                esac
            done
        fi
    }
    Get_Prop() {
        local tmp
        tmp=`echo $1=\`getprop $2\``
        export $tmp
    }
    Is_null() {
        if [[ "$1" == "" ]] ; then
            local tmp
            tmp=`echo $2=$3`
            # 不用eval，部分变量为导出日志用
            export $tmp
        else
            return 0
        fi
    }
    if [[ "$cpu_num" == "" && "$aff" == "" ]] ; then
        cpus=`cat /sys/devices/system/cpu/present`
        get_cpu_count "$cpus"
        cpu_aff=$(($cpu_num - 1))
        aff=0
        for i in `seq 1 +1 $cpu_aff` ; do
            aff+=",$i"
        done
    fi
  # function      new_var             prop
    Get_Prop 'dvdrss' 'dalvik.vm.dex2oat-resolve-startup-strings'
    Get_Prop 'updatable_bcp' 'dalvik.vm.dex2oat-updatable-bcp-packages-file'
    Get_Prop 'core_features' 'dalvik.vm.isa.arm64.features'
    Get_Prop 'image_format' 'dalvik.vm.appimageformat'
# function  old_value  var    new_value
    Is_null "$dvdrss" 'dvdrss' 'true'
    Is_null "$core_features" 'core_features' 'default'
    Is_null "$cpu_code" 'cpu_code' 'generic'
    Is_null "$image_format" 'image_format' 'lz4'
    [[ "$BOOTCLASSPATH" == "" ]] && err "BOOTCLASSPATH环境变量异常，请确保您使用的是真机而不是模拟器，确保是真机可能需要在根目录找init.environ.rc看看是否有错误的语法"
    if [ $api -ge 29 ]; then
        [[ "$DEX2OATBOOTCLASSPATH" == "" ]] && err "DEX2OATBOOTCLASSPATH环境变量异常，可能需要在根目录找init.environ.rc"
        if [ "$api" -ge "30" -a "$api" -le "32" ]; then
            if [ "$updatable_bcp" == "" ]; then
                [[ ! -f /system/etc/updatable-bcp-packages.txt ]] && err "updatable_bcp环境异常"
                updatable_bcp='/system/etc/updatable-bcp-packages.txt'
            fi
        fi
    fi
}

function profile_file_check()
{
    [[ `getprop dalvik.vm.usejitprofiles` == "false" ]] && echo " -Prop属性“dalvik.vm.usejitprofiles”不为true，本次编译无意义，安卓默认为true，请设置成true 无需清除数据安装一次软件再来" && return 1
    if [[ "$api" -le "30" ]] ; then
        [[ ! -f "/data/misc/profiles/cur/0/$package/primary.prof" ]] && echo " -CUR热点文件primary.prof未找到，您可能需要(无需清除数据重装一次软件)" && return 1
    fi
    return 0
}


# 首次
function clean_profile()
{
    if [[ "$force_compile" != "true" ]] ; then
        profile_file_check
        [ $? -eq 1 ] && return 0
    fi
    if [[ "$api" == "34" ]] ; then
        echo -n " --正在重置profile文件："
        cmd package art clear-app-profiles $package
        [ $? -ne 0 ] && cmd package compile --reset $package
        # 不清楚为什么安卓14的art在编译上通信有丢丢延迟，不应该减少0.5秒，后续谷歌应该会修复
        sleep 0.5
        echo -n "① -首次 $compile_filter-profile 编译(等待...) "
        cmd package compile -m "$compile_filter"-profile -f $package
    else
        echo -n "① -首次 $compile_filter-profile 编译(等待...) "
        cmd package compile -c -m "$compile_filter"-profile -f $package
    fi
    if [ "$xb" != "" ]; then
        if [ "$auto_profile" == "true" ]; then
            local file="/data/misc/profiles/cur/0/$package/primary.prof"
            if [[ "$auto_start" == "true" ]] ; then
                echo " --两分钟等待记录"
                echo " (若想马上终止 请按ctrl键，再按字母c即可)"
                echo -n '若无反应请手动启动 自动启动中...'
                mes=`am start $package  2>&1`
                [[ `echo "$mes" | grep 'Error: '` == "" ]] || am start -W `dumpsys package $package  2>&1 | grep -A 1 ' android.intent.action.MAIN:'  2>&1 | grep -v ' android.intent.action.MAIN:' | awk '{print $2}'  2>&1`  >/dev/null 2>&1
            else
                echo " --限两分钟内，请启动并静置软件$package 热点记录一生成自动杀掉app并自动往下编译"
                echo " (若想马上终止 请按ctrl键，再按字母c即可)"
                echo -n "正等待记录 请启动..."
            fi
            sleep 2
            for i in `seq 0 +1 120` ; do
                sleep 20
                if [[ `cat "$file"  2>/dev/null` != "" ]] ; then
                    sleep 10
                    am force-stop $package
                    echo "  热点记录已生成\n"
                    return 0
                fi
            done
            echo '  时间到'
        else
            echo '② -进行过首次profile编译无需再次profile编译(选项2)，您需要打开app后再进行profile编译'
            xb=""
        fi
    fi
}


# 再次
function profile()
{
    profile_file_check
    [ $? -eq 1 ] && return 0
    [[ "$(cat /data/misc/profiles/cur/0/$package/primary.prof  2>/dev/null)" == "" ]] && echo "② -热点文件primary.prof为空，无需再次编译，请去打开app生成热点记录再来编译，若热点仍为空，可能有以下原因：\n 1、启动app后等待的时间不够长，部分系统可能需要1分钟左右\n 2、app启动所需的代码量太少，达不到阈值jit不会对其进行记录，遇到这种情况该软件要么太小 要么软件已加固，您都无需进行完全编译，没有实际意义" && return 0
    echo -n "② -正在 $compile_filter-profile 编译(等待...) "
    am force-stop $package ; usleep 200000
    cmd package compile -m "$compile_filter"-profile -f $package
}


# 调用获取
function only_dump()
{
    if [ "$is_sys_package" == "true" ]; then
        if [[ "$xa" == "clean_profile" || "$xb" == "profile" || "$xc" == "compile" ]] ; then
            is_only_dump="false"
        else
            is_only_dump="true"
        fi
        [[ "$system_var" == "" ]] && system_var_sett
    else
        [[ "$user_var" == "" ]] && user_var_sett
    fi
    echo "④ -获取的文件保存到当前目录下oatdump.txt... "
    dumpp "oatdump.txt"
    echo 'Done'
}


# 隐藏选项，任意一个选项只要输入大小写ex并回车即触发
function EXT_ON()
{
    local EXT_c=''
    local EXT_filter=''
    local EXT_package=""
    echo ''
    echo ' ---已触发额外选项，进行单个cmd编译---'
    echo -n ' -是否清除热点列表：' ; read EXT_c
    case "$EXT_c" in
        Y | y)
            EXT_c='-c';;
        *)
            EXT_c='';;
    esac
    echo ' -自定义编译类型，可选(assume-verified、extract、verify、quicken、space-profile、space、speed、speed-profile、everything、everything-profile)'
    echo -n ' -请输入：' ; read EXT_filter
    [ "$EXT_filter" == "" ] && EXT_filter="$compile_filter"
    echo -n ' -包名(仅回车则使用默认包名)：' ; read EXT_package
    [ "$EXT_package" == "" ] && EXT_package="$package"
    if [[ "$api" == "34" ]] ; then
        if [[ "$EXT_c" != '' ]] ; then
            echo -n " --正在重置profile文件："
            cmd package art clear-app-profiles $package
            sleep 0.5
        fi
        echo " -命令行：cmd package compile -m "$EXT_filter" -f $EXT_package"
        cmd package compile -m $EXT_filter -f $EXT_package
    else
        echo " -命令行：cmd package compile "$EXT_c" -m "$EXT_filter" -f $EXT_package"
        cmd package compile $EXT_c -m $EXT_filter -f $EXT_package
    fi
}


# 获取
function dumpp()
{
    [[ ! -f "$odex" ]] && echo 'odex文件未找到' && return 1
    [[ `which oatdump` == "" ]] && err "oatdump工具未找到，无法获取信息"
    local output_file="$1"
    local tmp
    local s=1 t=0
    local temp='0.1'
    [[ "$output_file" == "" ]] && output_file=zzz.txt
    echo '' > ./"$output_file"
    usleep 150000
    echo "↓odex越大所需时间越长↓"
    while true; do
        echo "以$temp秒时间获取"
        timeout $temp oatdump --oat-file="$odex" --output=./"$output_file"
        tmp=$?
        if [[ "$tmp" == "0" ]] ; then
            if [[ "$(cat $output_file  2>&1)" != "" ]] ; then
            # timeout未到就dump完了
                # rm -f "$output_file"
                return 0
            else
            # 有可能这个odex不能被dump或其他异常
                # rm -f "$output_file"
                return 1
            fi  2>/dev/null
        fi
        if [[ "$(cat $output_file  2>/dev/null)" != "" ]] ; then
            # rm -f "$output_file"
            return 0
        else
            # temp=$(echo "scale=1; $temp + 0.1" | bc)
            # temp=`printf %.1f $temp`
            if [ $s -eq 9 ]; then
                s=0
                t=$(($t + 1))
            else
                s=$(($s + 1))
            fi
            temp="$t.$s"
        fi
        # 超过两秒还未dump出，不再继续dump（有可能出错也有可能是：毒瘤王，早日卸载吧！）
        # [[ $(echo "$temp > 2.0" | bc) == 1 ]] && return 1
        [[ "$t" -ge "2" && "$s" -gt "0" ]] && return 1
    done
}


# 完全编译总处理
function compile()
{
    force_profile_compile() {
        temp_compile="$compile_filter"
        compile_filter="speed-profile"
        touch "/data/misc/profiles/ref/$package/primary.prof"
        chown system:system "/data/misc/profiles/ref/$package/primary.prof"
        local cur_file
        cur_file="/data/misc/profiles/cur/0/$package/primary.prof"
        if [ ! -f "$cur_file" ]; then
            if [[ "$api" != "34" ]] ; then
                touch "$cur_file"
                chown system:system "$cur_file"
            fi
        else
            [[ `cat "$cur_file"  2>/dev/null` != "" ]] && true > "$cur_file"
        fi
    }
    force_compile_after() {
        [[ "$force_compile" == "true" ]] || return 0
        echo '强制profile编译完成'
        local cur_file="/data/misc/profiles/cur/0/$package/primary.prof"
        local ref_file="/data/misc/profiles/ref/$package/primary.prof"
        echo " --限两分钟内，请启动并静置软件$package 热点记录一生成自动杀掉app并自动往下编译"
        echo " (若想马上终止 请按ctrl键，再按字母c即可)"
        echo -n "正等待记录 请启动..."
        sleep 2
        for i in `seq 0 +1 120` ; do
            sleep 1
            if [[ `cat "$cur_file"  2>/dev/null` != "" ]] ; then
                am force-stop $package
                echo "  热点文件已生成，开始完全编译\n"
                cp -f "$cur_file" "$ref_file"
                if [[ "$api" == "34" ]] ; then
                    rm -f "$cur_file"
                else
                    true > "$cur_file"
                fi
                return 0
            fi
        done
        echo '  时间到'
        return 1
    }
    [[ "$force_compile" == "true" ]] && force_profile_compile
    profile_file_check
    [ $? -eq 1 ] && return 0
    [[ ! -f "/data/misc/profiles/ref/$package/primary.prof" ]] && echo " -REF热点文件primary.prof未找到，您可能需要首次profile编译(选项1)再次运行(选项2)，或者无需清数据重装一次软件，或某些特殊软件不允许有热点文件(尝试在选项3输入force)" && return 1
    if [ "$force_compile" != "true" ]; then
        if [[ "$(cat /data/misc/profiles/ref/$package/primary.prof  2>/dev/null)" == "" ]] ; then
            echo ''
            echo '------------------------------'
            echo "注： --因热点文件为空(可能未执行选项2)，本次完全编译生成的art文件仅仅是art文件本身所需的内容，本次编译等同于普通的$compile_filter编译，即便这样也为您继续编译......"
            echo '------------------------------'
        fi
    fi
    if [ "$is_sys_package" == "true" ]; then
        [[ "$system_var" == "" ]] && system_var_sett
    else
        [[ "$user_var" == "" ]] && user_var_sett
    fi
    echo -n '环境检查... '
    Env_Prop_Var
    echo 'success'
    echo '↓正在获取信息↓'
    dumpp "zzz.txt"
    case $? in
        0)
            echo '↑获取完成↑'
            ;;
        *)
            echo 'dump获取失败，不能继续编译(可能oat文件异常或过大)'
            return 0
            ;;
    esac
    [[ "$(cat zzz.txt  2>/dev/null)" == "" ]] && echo 'dump文件为空' && return 0
    class_loader_context=`grep 'dex2oat-cmdline ' zzz.txt | awk -F 'class-loader-context=' '{print $2}' | awk -F ' ' '{print $1}'`
    target_sdk_version=`grep 'dex2oat-cmdline ' zzz.txt | awk -F 'target-sdk-version:' '{print $2}' | awk -F ' ' '{print $1}'`
    if [[ "$api" == "34" ]] ; then
        comments=`grep 'dex2oat-cmdline ' zzz.txt | awk -F 'comments=' '{print $2}' | awk -F ' ' '{print $1}'`
        [[ "$comments" == "" ]] && err "dump文件未找到comments"
    fi
    [[ "$target_sdk_version" == "" ]] && err "dump文件未找到target_sdk_version"
    [[ "$class_loader_context" == "" ]] && err "dump文件未找到class_loader_context"
    # Remove target files
    if [[ "$is_user_package" == "true" ]] ; then
        for i in `find $cmd_path -type f -name 'base.art' -o -name 'base.odex' -o -name 'base.vdex'` ; do
            rm -f $i
        done
    else
        rm -f $art
        rm -f $odex
        rm -f $vdex
    fi
    # dex2oat命令
    dex2oat_command="dex2oat64"
    add_options=""
    api_mes() {
        am force-stop $package ; usleep 150000
        echo ''
        echo "③      -- 完全编译 --"
        echo " --当前API为$1，$2，开始"
    }

    case $api in
        28)
            api_mes "28" "安卓9"
            dex2oat_command="dex2oat"
            add_options="--runtime-arg -Xhidden-api-checks"
            ;;
        29)
            api_mes "29" "安卓10"
            dex2oat_command="dex2oat"
            add_options="--resolve-startup-const-strings=$dvdrss --runtime-arg -Xbootclasspath:$DEX2OATBOOTCLASSPATH --runtime-arg -Xhidden-api-policy:enabled"
            ;;
        30)
            api_mes "30" "安卓11"
            add_options="--resolve-startup-const-strings=$dvdrss --updatable-bcp-packages-file=$updatable_bcp --runtime-arg -Xbootclasspath:$DEX2OATBOOTCLASSPATH --runtime-arg -Xhidden-api-policy:enabled"
            ;;
        31)
            api_mes "31" "安卓12"
            add_options="--resolve-startup-const-strings=$dvdrss --updatable-bcp-packages-file=$updatable_bcp --runtime-arg -Xbootclasspath:$BOOTCLASSPATH --runtime-arg -Xhidden-api-policy:enabled"
            ;;
        32)
            api_mes "32" "安卓12L"
            add_options="--resolve-startup-const-strings=$dvdrss --updatable-bcp-packages-file=$updatable_bcp --runtime-arg -Xbootclasspath:$BOOTCLASSPATH --runtime-arg -Xhidden-api-policy:enabled --runtime-arg -Xdeny-art-apex-data-files"
            ;;
        33)
            api_mes "33" "安卓13"
            add_options="--resolve-startup-const-strings=$dvdrss --runtime-arg -Xbootclasspath:$BOOTCLASSPATH --runtime-arg -Xhidden-api-policy:enabled --runtime-arg -Xdeny-art-apex-data-files"
            ;;
        34)
            api_mes "34" "安卓14"
            add_options="--resolve-startup-const-strings=$dvdrss --runtime-arg -Xhidden-api-policy:enabled --comments=$comments"
            ;;
        "")
            err "  -API未找到，不再编译"
            ;;
        *)
            err " -API为$api，暂适配安卓9-14（api 28-34）"
            ;;
    esac

    if [[ "$is_sys_package" == "true" ]] ; then
        add_options="${add_options/' --runtime-arg -Xhidden-api-policy:enabled'/}"
        add_options="${add_options/' --runtime-arg -Xhidden-api-checks'/}"
        add_options+=" --compilation-reason=bg-dexopt"
    else
        # 利用install在MIUI或某些国内定制系统上能利用更多核心来编译(部分定制系统魔改过dex2oat源码，视传递的参数来选择核心，例如MIUI用install就能跑满、bg-dexopt会少几颗大核，update会少一两颗大核)，但是在原生和某些类原生上都无所谓 一样的。。无论如何这参数这只是一个信息传递 和编译之后的性能毫无关系
        add_options+=" --compilation-reason=install"
    fi
    add_options+=" $last_add_options"
    # 配置Dex2oat命令，绝对路径
    dex2oat_command=`which $dex2oat_command`
    if [ "$dex2oat_command" == "" ]; then
        if [ "$(which dex2oat32)" == "" ]; then
            echo ''
            echo 'ERROR：Dex2oat命令未找到，无法进行完全编译'
            return 1
        else
            dex2oat_command=`which dex2oat32`
        fi
    fi
    # 调用Dex2oat编译
    if [[ "$arm_code" == "two" && "$cpu_code" == "two" ]] ; then
        for i in `seq 1 +1 2` ; do
            case $i in
                1)  arm_code="arm"
                    cpu_code=`getprop 'dalvik.vm.isa.arm.variant'`
                    odex="${odex/'/arm64/'/'/arm/'}"
                    art="${art/'/arm64/'/'/arm/'}"
                    ;;
                2)  arm_code="arm64"
                    cpu_code=`getprop 'dalvik.vm.isa.arm64.variant'`
                    odex="${odex/'/arm/'/'/arm64/'}"
                    art="${art/'/arm/'/'/arm64/'}"
                    ;;
            esac
            [ "$cpu_code" == "" ] && cpu_code='generic'
            echo "双类型 正在编译$arm_code第$i次(共两次)"
            dex2oat_compile
            dex2oat_compile_result "$?"
        done
        if [ $? -eq 0 ]; then
            if [[ "$force_compile" == "true" ]] ; then
                force_compile_after
                if [ $? -eq 0 ]; then
                    force_compile=""
                    compile_filter="$temp_compile"
                    dex2oat_compile
                    dex2oat_compile_result "$?"
                fi
            fi
        fi
    else
        # 非双编译
        dex2oat_compile
        dex2oat_compile_result "$?"
        if [ $? -eq 0 ]; then
            if [[ "$force_compile" == "true" ]] ; then
                force_compile_after
                if [ $? -eq 0 ]; then
                    force_compile=""
                    compile_filter="$temp_compile"
                    dex2oat_compile
                    dex2oat_compile_result "$?"
                fi
            fi
        fi
    fi
}


# 编译后权限(避免某些系统bug)
function TooMany_and_system_SetPerm()
{
    [[ -f "$art" ]] && chown system:system "$art"
    [[ -f "$odex" ]] && chown system:system "$odex"
    [[ -f "$vdex" ]] && chown system:system "$vdex"
    [[ -f "$art" ]] && chmod 0644 "$art"
    [[ -f "$odex" ]] && chmod 0644 "$odex"
    [[ -f "$vdex" ]] && chmod 0644 "$vdex"
}  2>/dev/null


# 编译后该如何处理
function dex2oat_compile_result()
{
    case "$1" in
        0)
            if [[ "$is_user_package" == "true" ]] ; then
                if [[ "$is_too_many_package" == "true" ]] ; then
                    TooMany_and_system_SetPerm
                else
                    chown system:system $cmd_path/arm*/*
                    chmod 0644 $cmd_path/arm*/*
                fi
                echo "\n       ---- 编译成功 ----"
                echo ' -- 编译产物在软件路径/oat/下 --'
                echo ''
            else
                TooMany_and_system_SetPerm
                echo "\n       ---- 编译成功 ----"
                echo '-- 编译产物在/data/dalvik-cache/下 --'
                echo ''
            fi
            return 0
            ;;
        *)
            echo "\n！ERROR：编译失败，请检查日志dex2oat参数是否正确！由于失败未生成产物，您可能需要从头开始(选项1)\n"
            return 1
            ;;
    esac
}


# Dex2oat编译，调用编译器(真编译)
function dex2oat_compile()
{
# 通用配置，非最终参数，完整参数见add_options变量
$dex2oat_command \
--dex-file="$dex" --dex-location="$dex" \
--oat-file="$odex" --oat-location="$odex" \
--app-image-file="$art" \
--profile-file="/data/misc/profiles/ref/$package/primary.prof" \
--instruction-set="$arm_code" --instruction-set-variant="$cpu_code" --instruction-set-features=$core_features \
--runtime-arg -Xms64m --runtime-arg -Xmx512m \
--compiler-backend=Optimizing \
--compiler-filter=$compile_filter -j$cpu_num \
--no-generate-debug-info --no-generate-mini-debug-info \
--image-format=$image_format --very-large-app-threshold=2147483647 \
--compact-dex-level=fast \
--runtime-arg -Xtarget-sdk-version:$target_sdk_version \
--classpath-dir="$path" \
--class-loader-context="$class_loader_context" \
$add_options
return $?
}


# 日志
function output_log()
{
    echo "$(date "+%Y/%m/%d %T")\n"
    echo "已启用选项：$xa $xb $xc $xd $xe \n软件包名：$package \n软件名：$package_name \n软件路径：$path \n编译路径：$cmd_path \n软件位置：$dex \nart位置：$art \nodex位置：$odex \nvdex位置：$vdex \n系统软件：$is_sys_package \n系统更新软件：$is_sys_update_package \n第三方软件：$is_user_package \nAAB软件：$is_too_many_package \nAAB软件位置：$is_one_package \nAAB包最后个数：$one_package_count \n类型：$arm_code \n环境：$cpu_code \n特征：$core_features \n编译clc：$class_loader_context \n编译目标sdk：$target_sdk_version \n编译增添参数：$add_options \n报错：$error"
    echo "\n --- 环境变量 ---"
    env
}  2>/dev/null

# Start
input_package
output_log > $target_log
options_input
check_input_options
check_mt

# clean_profile
$xa

# profile
$xb

# compile
$xc

# only_dump
$xd

# EXT_ON
$xe

# 仅保存最近一次运行该脚本的日志
output_log > $target_log

# 删除dump后的文件
rm -f zzz.txt  2>/dev/null
