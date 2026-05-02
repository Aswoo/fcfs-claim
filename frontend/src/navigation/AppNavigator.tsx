import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { EnterScreen } from '../features/queue/EnterScreen';
import { WaitingScreen } from '../features/queue/WaitingScreen';
import { ReadyScreen } from '../features/queue/ReadyScreen';
import { ClaimScreen } from '../features/claim/ClaimScreen';

export type RootStackParamList = {
  Enter: undefined;
  Waiting: { rank: number; eventId: number; userId: number };
  Ready: { token: string; sequenceNumber: number };
  Claim: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export const AppNavigator = () => {
  return (
    <NavigationContainer>
      <Stack.Navigator screenOptions={{ headerShown: false }}>
        <Stack.Screen name="Enter" component={EnterScreen} />
        <Stack.Screen name="Waiting" component={WaitingScreen} />
        <Stack.Screen name="Ready" component={ReadyScreen} />
        <Stack.Screen name="Claim" component={ClaimScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
};
