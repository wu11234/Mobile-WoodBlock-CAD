@echo off
title WoodBlock 3D - YOLO Dataset Annotation Tool
cls
echo ========================================================
echo   WoodBlock 3D - YOLO Dataset Annotation Tool
echo   Image Directory: %~dp0dataset\images
echo ========================================================
echo.
echo   [Shortcuts]
echo   1. [Ctrl + R] : Create rectangle
echo   2. [D]        : Next image
echo   3. [A]        : Previous image
echo   4. [Ctrl + S] : Save annotations
echo.
echo   [Classes]
echo   - wood_block  : Outer wooden block boundary
echo   - hole        : Through-hole opening
echo   - groove      : Beveled sliding groove
echo.
echo ========================================================
echo Launching Labelme...
echo.

python -m labelme "%~dp0dataset\images" --output "%~dp0dataset\images" --labels "wood_block,hole,groove"

if errorlevel 1 (
    echo.
    echo Annotation tool closed.
    pause
)
