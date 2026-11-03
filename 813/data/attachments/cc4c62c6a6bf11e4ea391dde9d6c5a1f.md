## PUT http://localhost:43267/echo → 200 OK

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
date: Tue, 03 Nov 2026 17:51:35 GMT
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
curl 'http://localhost:43267/echo' \
  -X PUT \
  -d '{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}'
```
