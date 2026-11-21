## POST http://localhost:33897/echo → 200 OK

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
date: Sat, 21 Nov 2026 19:57:29 GMT
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
curl 'http://localhost:33897/echo' \
  -X POST \
  -d '{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}'
```
