"""
PyCOMPSs Testbench derived Classes with the same names
======================================================
"""

# Imports
from pycompss.api.task import task
from .myclass import myClass
from .myclass import inheritedClassWithOverride

class inheritedClass(myClass):
    pass

class inheritedClassWithOverride(myClass):
    @task(returns = int)
    def increment_non_modifier(self, v):
        print("self.value: ", self.value)
        print("V: ", v)
        return 2 * (self.value + v)

    @task()
    def increment_modifier(self, v):
        print("self.value: ", self.value)
        print("V: ", v)
        self.value = 2 * (self.value + v)

    @classmethod
    @task(returns = int)
    def increment(cls, v):
        return 2 * (cls.static_value + v)

class inheritedClassExtended(myClass):
    @task(returns = int)
    def multiplier_non_modifier(self, v):
        print("self.value: ", self.value)
        print("V: ", v)
        return self.value * v

    @task()
    def multiplier_modifier(self, v):
        print("self.value: ", self.value)
        print("V: ", v)
        self.value = self.value * v

    @classmethod
    @task(returns = int)
    def multiplier(cls, v):
        return cls.static_value * v

class inheritedClassMultilevelOverridedExtended(inheritedClassWithOverride):
    @task(returns = int)
    def divider_non_modifier(self, v):
        print("self.value: ", self.value)
        print("V: ", v)
        return self.value / v

    @task()
    def divider_modifier(self, v):
        print("self.value: ", self.value)
        print("V: ", v)
        self.value = self.value / v

    @classmethod
    @task(returns = int)
    def divider(cls, v):
        return cls.static_value / v
