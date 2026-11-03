## PUT http://localhost:37963/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Tue, 03 Nov 2026 17:51:39 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:37963/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
