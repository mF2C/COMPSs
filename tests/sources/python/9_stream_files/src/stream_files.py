#!/usr/bin/python

# -*- coding: utf-8 -*-

"""
PyCOMPSs Testbench
==================
    This file tests the Python FILE Streams implementation in PyCOMPSs.
"""

# Imports
from pycompss.api.api import compss_wait_on
from pycompss.streams.distro_stream import FileDistroStream

from modules.test_tasks import write_files
from modules.test_tasks import read_files
from modules.test_tasks import process_file
from modules.test_tasks import TEST_PATH

PRODUCER_SLEEP = 0.2  # s
CONSUMER_SLEEP = 0.1  # s
CONSUMER_SLEEP2 = 0.3  # s
ALIAS = "py_file_stream"


def create_folder(folder):
    import os
    os.mkdir(folder)


def clean_folder(folder):
    import shutil
    shutil.rmtree(folder, ignore_errors=True)


def test_produce_consume(num_producers, producer_sleep, num_consumers, consumer_sleep):
    # Clean and create test path
    clean_folder(TEST_PATH)
    create_folder(TEST_PATH)

    # Create stream
    fds = FileDistroStream(base_dir=TEST_PATH)

    # Create producers
    for _ in range(num_producers):
        write_files(fds, producer_sleep)

    # Create consumers
    total_files = []
    for i in range(num_consumers):
        num_files = read_files(fds, consumer_sleep)
        total_files.append(num_files)

    # Sync and print value
    total_files = compss_wait_on(total_files)
    num_total = sum(total_files)
    print("[LOG] TOTAL NUMBER OF PROCESSED FILES: " + str(num_total))


def test_produce_gen_tasks(num_producers, producer_sleep, consumer_sleep):
    import time

    # Clean and create test path
    clean_folder(TEST_PATH)
    create_folder(TEST_PATH)

    # Create stream
    fds = FileDistroStream(base_dir=TEST_PATH)

    # Create producers
    for _ in range(num_producers):
        write_files(fds, producer_sleep)

    # Process stream
    processed_results = []
    while not fds.is_closed():
        # Poll new files
        print("Polling files")
        new_files = fds.poll()

        # Process files
        for nf in new_files:
            res = process_file(nf)
            processed_results.append(res)

        # Sleep between requests
        time.sleep(consumer_sleep)

    # Sync and accumulate read files
    processed_results = compss_wait_on(processed_results)
    num_total = sum(processed_results)
    print("[LOG] TOTAL NUMBER OF PROCESSED FILES: " + str(num_total))


def test_by_alias(num_producers, producer_sleep, num_consumers, consumer_sleep):
    # Clean and create test path
    clean_folder(TEST_PATH)
    create_folder(TEST_PATH)

    # Create producers
    fds = FileDistroStream(alias=ALIAS, base_dir=TEST_PATH)
    for _ in range(num_producers):
        write_files(fds, producer_sleep)

    # Create consumers
    fds2 = FileDistroStream(alias=ALIAS, base_dir=TEST_PATH)
    total_files = []
    for i in range(num_consumers):
        num_files = read_files(fds2, consumer_sleep)
        total_files.append(num_files)

    # Sync and print value
    total_files = compss_wait_on(total_files)
    num_total = sum(total_files)
    print("[LOG] TOTAL NUMBER OF PROCESSED FILES: " + str(num_total))


def main_program():
    # 1 producer, 1 consumer, consumerTime < producerTime
    test_produce_consume(1, PRODUCER_SLEEP, 1, CONSUMER_SLEEP)

    # 1 producer, 1 consumer, consumerTime > producerTime
    test_produce_consume(1, PRODUCER_SLEEP, 1, CONSUMER_SLEEP2)

    # 1 producer, 2 consumer
    test_produce_consume(1, PRODUCER_SLEEP, 2, CONSUMER_SLEEP2)

    # 2 producer, 1 consumer
    test_produce_consume(2, PRODUCER_SLEEP, 1, CONSUMER_SLEEP)

    # 2 producer, 2 consumer
    test_produce_consume(2, PRODUCER_SLEEP, 2, CONSUMER_SLEEP)

    # 1 producer, 1 task per entry
    test_produce_gen_tasks(1, PRODUCER_SLEEP, CONSUMER_SLEEP)

    # By alias
    test_by_alias(1, PRODUCER_SLEEP, 1, CONSUMER_SLEEP)

    # Clean folder
    clean_folder(TEST_PATH)


if __name__ == "__main__":
    main_program()
