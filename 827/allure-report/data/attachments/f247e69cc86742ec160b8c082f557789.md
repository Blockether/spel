## DELETE http://localhost:40979/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 11 Nov 2026 20:01:46 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:40979/echo' \
  -X DELETE
```
