import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Colors } from '../../constants/colors';

interface Props {
  title: string;
  count: string;
}

export const SectionHeader = React.memo(({ title, count }: Props) => {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>{title}</Text>
      <Text style={styles.count}>{count}</Text>
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    height: 38,
    backgroundColor: Colors.cream,
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    paddingTop: 14,
    paddingHorizontal: 14,
    paddingBottom: 8,
  },
  title: {
    fontSize: 14,
    fontWeight: '700',
    color: Colors.textPrimary,
  },
  count: {
    fontSize: 11,
    fontWeight: '400',
    color: Colors.textMuted,
  },
});
