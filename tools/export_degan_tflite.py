#!/usr/bin/env python3
"""
DE-GAN Generator → TFLite 转换脚本

使用 Keras 构建模型，加载权重，导出为 TFLite。
启用 TF Select ops 支持 UpSampling2D + Conv2D(2x2)。
"""

import os
import sys
import argparse
import numpy as np

def build_generator(input_size=(256, 256, 1), biggest_layer=512):
    """构建 DE-GAN Generator (Keras UNet)"""
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras.layers import (
        Input, Conv2D, MaxPooling2D, Dropout, UpSampling2D,
        Concatenate
    )
    from tensorflow.keras.models import Model

    inputs = Input(input_size)
    conv1 = Conv2D(64, 3, activation='relu', padding='same', kernel_initializer='he_normal')(inputs)
    conv1 = Conv2D(64, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv1)
    pool1 = MaxPooling2D(pool_size=(2, 2))(conv1)
    conv2 = Conv2D(128, 3, activation='relu', padding='same', kernel_initializer='he_normal')(pool1)
    conv2 = Conv2D(128, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv2)
    pool2 = MaxPooling2D(pool_size=(2, 2))(conv2)
    conv3 = Conv2D(256, 3, activation='relu', padding='same', kernel_initializer='he_normal')(pool2)
    conv3 = Conv2D(256, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv3)
    pool3 = MaxPooling2D(pool_size=(2, 2))(conv3)
    conv4 = Conv2D(biggest_layer//2, 3, activation='relu', padding='same', kernel_initializer='he_normal')(pool3)
    conv4 = Conv2D(biggest_layer//2, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv4)
    drop4 = Dropout(0.5)(conv4)
    pool4 = MaxPooling2D(pool_size=(2, 2))(drop4)

    conv5 = Conv2D(biggest_layer, 3, activation='relu', padding='same', kernel_initializer='he_normal')(pool4)
    conv5 = Conv2D(biggest_layer, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv5)
    drop5 = Dropout(0.5)(conv5)

    up6 = Conv2D(512, 2, activation='relu', padding='same', kernel_initializer='he_normal')(UpSampling2D(size=(2,2))(drop5))
    merge6 = Concatenate()([drop4, up6])
    conv6 = Conv2D(512, 3, activation='relu', padding='same', kernel_initializer='he_normal')(merge6)
    conv6 = Conv2D(512, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv6)

    up7 = Conv2D(256, 2, activation='relu', padding='same', kernel_initializer='he_normal')(UpSampling2D(size=(2,2))(conv6))
    merge7 = Concatenate()([conv3, up7])
    conv7 = Conv2D(256, 3, activation='relu', padding='same', kernel_initializer='he_normal')(merge7)
    conv7 = Conv2D(256, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv7)

    up8 = Conv2D(128, 2, activation='relu', padding='same', kernel_initializer='he_normal')(UpSampling2D(size=(2,2))(conv7))
    merge8 = Concatenate()([conv2, up8])
    conv8 = Conv2D(128, 3, activation='relu', padding='same', kernel_initializer='he_normal')(merge8)
    conv8 = Conv2D(128, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv8)

    up9 = Conv2D(64, 2, activation='relu', padding='same', kernel_initializer='he_normal')(UpSampling2D(size=(2,2))(conv8))
    merge9 = Concatenate()([conv1, up9])
    conv9 = Conv2D(64, 3, activation='relu', padding='same', kernel_initializer='he_normal')(merge9)
    conv9 = Conv2D(64, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv9)
    conv9 = Conv2D(2, 3, activation='relu', padding='same', kernel_initializer='he_normal')(conv9)
    conv10 = Conv2D(1, 1, activation='sigmoid')(conv9)

    model = Model(inputs=inputs, outputs=conv10)
    return model


def export_tflite(weights_path, output_path):
    """加载权重并导出 TFLite"""
    import tensorflow as tf
    import h5py

    # 构建原始单通道模型
    print("Building model (1-channel input, 256x256)...")
    model = build_generator(input_size=(256, 256, 1), biggest_layer=512)
    print(f"Model params: {model.count_params()}")

    # 尝试加载权重
    if weights_path and os.path.exists(weights_path):
        print(f"Loading weights from: {weights_path}")

        # 检查文件格式
        with open(weights_path, 'rb') as f:
            magic = f.read(8)
        print(f"File magic bytes: {magic[:4]}")

        try:
            model.load_weights(weights_path)
            print("Weights loaded successfully!")
        except Exception as e:
            print(f"load_weights failed: {e}")
            # 尝试用 h5py 检查文件结构
            try:
                with h5py.File(weights_path, 'r') as f:
                    print("HDF5 keys:", list(f.keys()))
                    if 'model_weights' in f:
                        print("model_weights keys:", list(f['model_weights'].keys())[:5])
                    # 尝试 by_name
                    try:
                        model.load_weights(weights_path, by_name=True)
                        print("Weights loaded by_name!")
                    except Exception as e2:
                        print(f"by_name also failed: {e2}")
                        print("Using random weights")
            except Exception as e3:
                print(f"Not an HDF5 file: {e3}")
                # 可能是 PyTorch checkpoint
                print("File may be PyTorch format. Using random weights.")
    else:
        print("No weights file. Using random weights.")

    # 导出为 TFLite，启用 TF Select ops 支持 Conv2D(2x2) after UpSampling2D
    print("\nConverting to TFLite (with TF Select ops)...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float32]

    # 启用 TF Select ops 来支持 2x2 Conv2D
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]

    tflite_model = converter.convert()

    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"\nTFLite saved: {output_path} ({size_mb:.1f} MB)")
    print(f"Copy to: app/src/main/assets/models/doc_enhance.tflite")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Export DE-GAN generator to TFLite')
    parser.add_argument('--weights', type=str, default=None,
                        help='Path to weights file (.h5)')
    parser.add_argument('--output', type=str, default='doc_enhance.tflite',
                        help='Output TFLite file path')
    args = parser.parse_args()

    export_tflite(args.weights, args.output)
