from setuptools import setup, find_packages
setup(
    name='cloudmeter-client',
    version='0.1.0',
    description='CloudMeter client for Python web frameworks (Flask, FastAPI, Django)',
    packages=find_packages(where='src'),
    package_dir={'': 'src'},
    python_requires='>=3.8',
    extras_require={
        'flask': ['flask>=2.0'],
        'fastapi': ['starlette>=0.20'],
        'django': ['django>=3.2'],
    },
)
