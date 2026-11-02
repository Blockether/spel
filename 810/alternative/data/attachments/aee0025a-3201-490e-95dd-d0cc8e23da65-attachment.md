## POST http://localhost:38119/echo → 200 OK

### Request Body
```json
{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}
```

### Response Headers
```
content-length: 95
content-type: application/json
date: Mon, 02 Nov 2026 18:55:32 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Bob",
    "email": "bob@example.org",
    "role": "admin"
  }
}
```

### cURL
```bash
curl 'http://localhost:38119/echo' \
  -X POST \
  -d '{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}'
```
