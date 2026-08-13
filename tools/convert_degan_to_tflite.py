#!/usr/bin/env python3
"""
DE-GAN / DP-LinkNet PyTorch → TFLite 转换脚本

使用方法:
  1. 安装依赖: pip install torch torchvision onnx tensorflow onnx-tf
  2. 下载 DE-GAN 预训练权重 (从 https://github.com/RichSu95/Document_Binarization_Collection)
  3. 运行: python convert_degan_to_tflite.py --model degan --weights path/to/weights.pth --output doc_enhance.tflite
  4. 将输出的 doc_enhance.tflite 放到 app/src/main/assets/models/

模型架构: UNet-based, 输入 [1, H, W, 3] RGB 0~1, 输出 [1, H, W, 1] 二值概率
"""

import argparse
import sys
import os

def convert_degan(weights_path, output_path, input_size=(1, 256, 256, 3)):
    """转换 DE-GAN 模型"""
    try:
        import torch
        import torch.nn as nn
        import torch.nn.functional as F
    except ImportError:
        print("Error: PyTorch not installed. Run: pip install torch torchvision")
        sys.exit(1)

    # DE-GAN Generator (UNet with dilated convolutions)
    class DoubleConv(nn.Module):
        def __init__(self, in_ch, out_ch):
            super().__init__()
            self.conv = nn.Sequential(
                nn.Conv2d(in_ch, out_ch, 3, padding=1),
                nn.BatchNorm2d(out_ch),
                nn.ReLU(inplace=True),
                nn.Conv2d(out_ch, out_ch, 3, padding=1),
                nn.BatchNorm2d(out_ch),
                nn.ReLU(inplace=True)
            )
        def forward(self, x):
            return self.conv(x)

    class Down(nn.Module):
        def __init__(self, in_ch, out_ch):
            super().__init__()
            self.mpconv = nn.Sequential(
                nn.MaxPool2d(2),
                DoubleConv(in_ch, out_ch)
            )
        def forward(self, x):
            return self.mpconv(x)

    class Up(nn.Module):
        def __init__(self, in_ch, out_ch):
            super().__init__()
            self.up = nn.Upsample(scale_factor=2, mode='bilinear', align_corners=True)
            self.conv = DoubleConv(in_ch, out_ch)
        def forward(self, x1, x2):
            x1 = self.up(x1)
            diffY = x2.size()[2] - x1.size()[2]
            diffX = x2.size()[3] - x1.size()[3]
            x1 = F.pad(x1, [diffX // 2, diffX - diffX // 2,
                            diffY // 2, diffY - diffY // 2])
            x = torch.cat([x2, x1], dim=1)
            return self.conv(x)

    class UNet(nn.Module):
        def __init__(self, n_channels=3, n_classes=1):
            super().__init__()
            self.inc = DoubleConv(n_channels, 64)
            self.down1 = Down(64, 128)
            self.down2 = Down(128, 256)
            self.down3 = Down(256, 512)
            self.down4 = Down(512, 512)
            self.up1 = Up(1024, 256)
            self.up2 = Up(512, 128)
            self.up3 = Up(256, 64)
            self.up4 = Up(128, 64)
            self.outc = nn.Conv2d(64, n_classes, 1)

        def forward(self, x):
            x1 = self.inc(x)
            x2 = self.down1(x1)
            x3 = self.down2(x2)
            x4 = self.down3(x3)
            x5 = self.down4(x4)
            x = self.up1(x5, x4)
            x = self.up2(x, x3)
            x = self.up3(x, x2)
            x = self.up4(x, x1)
            x = self.outc(x)
            return torch.sigmoid(x)

    # 加载模型
    model = UNet(n_channels=3, n_classes=1)
    try:
        checkpoint = torch.load(weights_path, map_location='cpu')
        if 'state_dict' in checkpoint:
            model.load_state_dict(checkpoint['state_dict'])
        elif 'model' in checkpoint:
            model.load_state_dict(checkpoint['model'])
        else:
            model.load_state_dict(checkpoint)
    except Exception as e:
        print(f"Warning: Could not load weights directly: {e}")
        print("Using random weights (for architecture testing only)")

    model.eval()

    # 转换为 ONNX
    dummy_input = torch.randn(1, 3, 256, 256)
    onnx_path = output_path.replace('.tflite', '.onnx')

    print("Exporting to ONNX...")
    torch.onnx.export(
        model, dummy_input, onnx_path,
        opset_version=11,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={
            'input': {0: 'batch', 2: 'height', 3: 'width'},
            'output': {0: 'batch', 2: 'height', 3: 'width'}
        }
    )
    print(f"ONNX saved: {onnx_path}")

    # 转换为 TFLite
    try:
        import tensorflow as tf
        from onnx_tf.backend import prepare
        import onnx
    except ImportError:
        print("Error: TensorFlow or onnx-tf not installed.")
        print("Run: pip install tensorflow onnx onnx-tf")
        sys.exit(1)

    print("Converting ONNX → TF...")
    onnx_model = onnx.load(onnx_path)
    tf_rep = prepare(onnx_model)
    tf_model_dir = output_path.replace('.tflite', '_tf')
    tf_rep.export_graph(tf_model_dir)

    print("Converting TF → TFLite...")
    converter = tf.lite.TFLiteConverter.from_saved_model(tf_model_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float32]

    # 支持 GPU delegate
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]

    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"TFLite saved: {output_path} ({size_mb:.1f} MB)")
    print("\nCopy to: app/src/main/assets/models/doc_enhance.tflite")


def convert_dplinknet(weights_path, output_path):
    """转换 DP-LinkNet 模型"""
    try:
        import torch
        import torch.nn as nn
    except ImportError:
        print("Error: PyTorch not installed.")
        sys.exit(1)

    # DP-LinkNet 基于 LinkNet 架构
    # 这里简化实现，实际使用时需要根据原项目代码调整
    print("DP-LinkNet conversion - please refer to the original project for model architecture")
    print("https://github.com/RichSu95/Document_Binarization_Collection/tree/master/DP-LinkNet")
    convert_degan(weights_path, output_path)  # fallback to UNet


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Convert document binarization model to TFLite')
    parser.add_argument('--model', choices=['degan', 'dplinknet', 'sauvolanet'], default='degan',
                        help='Model architecture')
    parser.add_argument('--weights', type=str, required=False,
                        help='Path to PyTorch weights file')
    parser.add_argument('--output', type=str, default='doc_enhance.tflite',
                        help='Output TFLite file path')
    args = parser.parse_args()

    if args.model == 'degan':
        convert_degan(args.weights, args.output)
    elif args.model == 'dplinknet':
        convert_dplinknet(args.weights, args.output)
    elif args.model == 'sauvolanet':
        print("SauvolaNet conversion not yet implemented")
        print("Please refer to: https://github.com/leedh/SauvolaNet")
