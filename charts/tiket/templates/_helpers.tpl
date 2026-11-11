{{- define "tiket.name" -}}
{{- if gt (len .Release.Name) 40 -}}
{{ .Release.Name | trunc 31 | trimSuffix "-" }}-{{ .Release.Name | sha256sum | trunc 8 }}
{{- else -}}{{ .Release.Name }}{{- end -}}
{{- end }}
{{- define "tiket.labels" -}}
app.kubernetes.io/name: tiket
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
{{- define "tiket.image" -}}
{{- if .Values.image.digest -}}
{{ .Values.image.repository }}@{{ .Values.image.digest }}
{{- else -}}
{{ .Values.image.repository }}:{{ default .Chart.AppVersion .Values.image.tag }}
{{- end -}}
{{- end }}
{{- define "tiket.sa" -}}{{ default (include "tiket.name" .) .Values.serviceAccount.name }}{{- end }}
{{- define "tiket.security" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
{{- end }}
{{- define "tiket.dbEnv" -}}
- name: DB_URL
  value: {{ .Values.database.url | quote }}
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: username
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: password
{{- end }}
