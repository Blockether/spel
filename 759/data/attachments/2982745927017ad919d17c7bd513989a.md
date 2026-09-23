## DELETE http://localhost:36253/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 23 Sep 2026 09:57:25 GMT
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
curl 'http://localhost:36253/echo' \
  -X DELETE
```
