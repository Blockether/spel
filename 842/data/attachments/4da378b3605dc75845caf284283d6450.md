## PUT http://localhost:37289/echo → 200 OK

### Request Body
```json
{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}
```

### Response Headers
```
content-length: 92
content-type: application/json
date: Sun, 22 Nov 2026 01:23:00 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Alice Updated",
    "email": "alice2@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:37289/echo' \
  -X PUT \
  -d '{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}'
```
