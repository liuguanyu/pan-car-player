# -*- coding: utf-8 -*-
"""
检查Android应用日志的脚本
使用方法: python check_logs.py
"""

import subprocess
import sys

def run_command(cmd):
    """执行命令并返回输出"""
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding='utf-8')
        return result.stdout
    except Exception as e:
        print(f"执行命令失败: {e}")
        return None

def main():
    print("=" * 60)
    print("检查百度车载播放器日志")
    print("=" * 60)
    print()
    
    # 清空之前的日志
    print("1. 清空之前的日志...")
    run_command("adb logcat -c")
    print("   完成")
    print()
    
    print("2. 开始监听日志 (按Ctrl+C停止)")
    print("   关注以下关键信息:")
    print("   - playAtPosition: 播放位置和seek参数")
    print("   - pendingSeekPosition: pending seek状态")
    print("   - play(url): 播放URL和playWhenReady设置")
    print("   - STATE_READY: 播放器准备就绪")
    print("   - 执行待处理的seek操作: seek执行情况")
    print("   - 播放状态: 播放还是暂停")
    print()
    print("-" * 60)
    
    # 实时显示日志，过滤AudioPlayerService的TAG
    try:
        process = subprocess.Popen(
            "adb logcat AudioPlayerService:D *:S",
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            bufsize=1
        )
        
        for line in process.stdout:
            print(line, end='')
            
    except KeyboardInterrupt:
        print()
        print("-" * 60)
        print("日志监听已停止")
        process.terminate()

if __name__ == "__main__":
    main()