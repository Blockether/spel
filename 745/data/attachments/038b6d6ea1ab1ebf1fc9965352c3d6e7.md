## DELETE http://localhost:39803/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 11 Sep 2026 11:38:08 GMT
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
curl 'http://localhost:39803/echo' \
  -X DELETE
```
