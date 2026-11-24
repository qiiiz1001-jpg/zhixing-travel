<script setup>
import TravelLogin from './components/TravelLogin.vue'
import TripWorkspace from './components/TripWorkspace.vue'
import { user } from './api'
</script>

<template>
  <TripWorkspace v-if="user" :key="user.id" :person="user" />
  <TravelLogin v-else />
</template>

