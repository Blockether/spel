## DELETE http://localhost:34911/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 05 Sep 2026 18:46:25 GMT
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
curl 'http://localhost:34911/echo' \
  -X DELETE
```
