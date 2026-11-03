## POST http://localhost:37963/echo → 200 OK

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
date: Tue, 03 Nov 2026 17:51:39 GMT
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
curl 'http://localhost:37963/echo' \
  -X POST \
  -d '{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}'
```
