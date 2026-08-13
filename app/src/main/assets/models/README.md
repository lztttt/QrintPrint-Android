# 文档增强模型 (TFLite)

将 DE-GAN / DP-LinkNet / SauvolaNet 转换为 TFLite 格式并放入此目录，供错题本功能使用。

## 快速开始

1. 安装 Python 依赖:
   ```bash
   pip install torch torchvision onnx tensorflow onnx-tf
   ```

2. 从 GitHub 获取预训练权重:
   - DE-GAN: https://github.com/RichSu95/Document_Binarization_Collection/tree/master/DE-GAN/models
   - DP-LinkNet: https://github.com/RichSu95/Document_Binarization_Collection/tree/master/DP-LinkNet/models

3. 运行转换脚本:
   ```bash
   python ../../tools/convert_degan_to_tflite.py --model degan --weights path/to/weights.pth --output doc_enhance.tflite
   ```

4. 将输出的 `doc_enhance.tflite` 复制到本目录

## 模型要求

- **输入形状**: `[1, H, W, 3]` float32 (RGB, 0~255)
- **输出形状**: `[1, H, W, 1]` float32 (二值概率, 0=白, 1=黑)
- **推荐大小**: 10~20MB

如未放置模型，错题本功能会自动使用 Sauvola 自适应二值化算法作为后备方案。

## 其他来源

你也可以从以下项目获取 TFLite 模型:

- [DocUNet](https://github.com/DocUNet) (需自行转换为 TFLite)
- [Text-Recognition-System](https://github.com/VitaliyDatsyshyn/Text-Recognition-System) 包含二值化模型